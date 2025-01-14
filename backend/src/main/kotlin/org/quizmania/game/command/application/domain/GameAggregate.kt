package org.quizmania.game.command.application.domain

import mu.KLogging
import org.axonframework.commandhandling.CommandExecutionException
import org.axonframework.commandhandling.CommandHandler
import org.axonframework.deadline.annotation.DeadlineHandler
import org.axonframework.eventsourcing.EventSourcingHandler
import org.axonframework.messaging.Message
import org.axonframework.messaging.interceptors.ExceptionHandler
import org.axonframework.modelling.command.*
import org.axonframework.spring.stereotype.Aggregate
import org.quizmania.common.axon.problem.CommandExecutionProblem
import org.quizmania.game.api.*
import org.quizmania.game.command.adapter.out.DeadlineScheduler
import org.quizmania.game.command.port.out.QuestionPort
import org.quizmania.question.api.QuestionId
import java.time.Duration
import java.time.Instant
import java.util.*

@Aggregate
internal class GameAggregate() {

  companion object : KLogging() {
    object Deadline {
      const val GAME_ABANDONED = "gameAbandonedDeadline"
      const val QUESTION_CLOSE = "questionCloseDeadline"
      const val QUESTION_BUZZER = "questionBuzzerDeadline"
    }
  }

  @AggregateIdentifier
  private lateinit var gameId: UUID
  private lateinit var config: GameConfig
  private lateinit var questionList: List<QuestionId>
  private var questionsAsked: Int = 0
  private var moderatorUsername: String? = null
  private var gameStatus: GameStatus = GameStatus.CREATED

  private var users: MutableList<User> = mutableListOf()

  @AggregateMember(eventForwardingMode = ForwardMatchingInstances::class)
  private var askedQuestions: MutableList<GameQuestion> = mutableListOf()

  @CommandHandler
  @CreationPolicy(AggregateCreationPolicy.ALWAYS)
  fun create(command: CreateGameCommand, questionPort: QuestionPort, deadlineScheduler: DeadlineScheduler) {
    logger.info { "Executing CreateGameCommand for game ${command.gameId}" }

    val questionSet = questionPort.getQuestionSet(command.config.questionSetId)
    val realNumQuestions = command.config.numQuestions.coerceAtMost(questionSet.questions.size)

    if (command.config.useBuzzer && command.moderatorUsername == null) {
      throw InvalidConfigProblem(command.gameId, "Buzzer game needs a moderator")
    }

    AggregateLifecycle.apply(
      GameCreatedEvent(
        command.gameId,
        command.name,
        command.config.copy(
          numQuestions = realNumQuestions // adjust question number to questionSet
        ),
        questionSet.questions.take(realNumQuestions),
        command.creatorUsername,
        command.moderatorUsername
      )
    )

    deadlineScheduler.schedule(Duration.ofMinutes(30), Deadline.GAME_ABANDONED, command.gameId)
  }

  @CommandHandler
  fun handle(command: AddUserCommand) {
    logger.info { "Executing AddUserCommand for game ${command.gameId} and user ${command.username}" }
    if (users.size < config.maxPlayers) {
      if (!users.containsUsername(command.username) && moderatorUsername != command.username) {
        AggregateLifecycle.apply(
          UserAddedEvent(
            command.gameId,
            UUID.randomUUID(),
            command.username
          )
        )
      } else {
        throw UsernameTakenProblem(this.gameId)
      }
    } else {
      throw GameAlreadyFullProblem(this.gameId)
    }
  }

  @CommandHandler
  fun handle(command: RemoveUserCommand) {
    logger.info { "Executing RemoveUserCommand for game ${command.gameId} and user ${command.username}" }
    users.findByUsername(command.username)?.let { user ->
      AggregateLifecycle.apply(
        UserRemovedEvent(
          command.gameId,
          user.gameUserId,
          user.username
        )
      )

      if (command.username == this.moderatorUsername || this.users.size == 0) {
        AggregateLifecycle.apply(
          GameCanceledEvent(gameId)
        )
      }
    }
  }

  @CommandHandler
  fun handle(command: StartGameCommand, questionPort: QuestionPort, deadlineScheduler: DeadlineScheduler) {
    logger.info { "Executing StartGameCommand for game ${command.gameId}" }
    if (config.useBuzzer && this.users.size < 2) {
      throw InvalidConfigProblem(this.gameId, "Buzzer game needs at least two players")
    }
    if (this.gameStatus != GameStatus.CREATED) {
      throw GameAlreadyStartedProblem(this.gameId)
    }

    AggregateLifecycle.apply(GameStartedEvent(command.gameId))
    askNextQuestion(questionPort, deadlineScheduler)
  }

  @CommandHandler
  fun handle(command: AnswerQuestionCommand, deadlineScheduler: DeadlineScheduler) {
    logger.info { "Executing AnswerQuestionCommand for game ${command.gameId}: $command" }
    assertStarted()

    val user = users.getByUsername(command.username)
    val gameQuestion = askedQuestions.getById(command.gameQuestionId)
    gameQuestion.answer(user.gameUserId, command.answer)

    // after QuestionAnsweredEvent is applied, the user-answer is actually in the list
    if (users.size == gameQuestion.numAnswers()) {
      gameQuestion.closeQuestion()
      deadlineScheduler.cancel(Deadline.QUESTION_CLOSE, this.gameId)
    }
  }

  @CommandHandler
  fun handle(command: OverrideAnswerCommand) {
    logger.info { "Executing OverrideAnswerCommand for game ${command.gameId}: $command" }
    assertStarted()

    val gameQuestion = askedQuestions.getById(command.gameQuestionId)
    gameQuestion.overrideAnswer(command.gameUserId, command.answer)
  }

  @CommandHandler
  fun handle(command: BuzzQuestionCommand, deadlineScheduler: DeadlineScheduler) {
    logger.info { "Executing BuzzQuestionCommand for game ${command.gameId}: $command" }
    assertStarted()

    val user = users.getByUsername(command.username)
    val gameQuestion = askedQuestions.getById(command.gameQuestionId)

    gameQuestion.buzz(user.gameUserId, command.buzzerTimestamp)

    if (gameQuestion.numBuzzers() == 1) {
      deadlineScheduler.schedule(Duration.ofMillis(500), Deadline.QUESTION_BUZZER, this.gameId)
    }
  }

  @CommandHandler
  fun handle(command: AnswerBuzzerQuestionCommand) {
    logger.info { "Executing AnswerBuzzerQuestionCommand for game ${command.gameId}: $command" }
    assertStarted()

    val gameQuestion = askedQuestions.getById(command.gameQuestionId)

    gameQuestion.answerBuzzWinner(command.answerCorrect)
  }

  @CommandHandler
  fun handle(command: CloseQuestionCommand, deadlineScheduler: DeadlineScheduler) {
    logger.info { "Executing CloseQuestionCommand for game ${command.gameId}: $command" }
    assertStarted()

    askedQuestions.getById(command.gameQuestionId)
      .closeQuestion()

    deadlineScheduler.cancel(Deadline.QUESTION_CLOSE, this.gameId)
  }

  @CommandHandler
  fun handle(command: RateQuestionCommand) {
    logger.info { "Executing RateQuestionCommand for game ${command.gameId}: $command" }
    assertStarted()

    val gameQuestion =
      askedQuestions.getById(command.gameQuestionId)
    if (gameQuestion.isRated()) {
      throw QuestionAlreadyClosedProblem(gameId, command.gameQuestionId)
    }

    gameQuestion.rateQuestion()
  }


  @CommandHandler
  fun handle(command: AskNextQuestionCommand, questionPort: QuestionPort, deadlineScheduler: DeadlineScheduler) {
    logger.info { "Executing AskNextQuestionCommand for game ${command.gameId}: $command" }
    assertStarted()

    if (askedQuestions.any { !it.isRated() }) {
      throw OtherQuestionStillOpenProblem(gameId)
    }
    if (this.askedQuestions.size >= this.config.numQuestions) {
      endGame(deadlineScheduler)
    } else {
      askNextQuestion(questionPort, deadlineScheduler)
    }
  }

  private fun assertStarted() {
    if (gameStatus != GameStatus.STARTED) {
      throw GameAlreadyEndedProblem(gameId)
    }
  }

  @EventSourcingHandler
  fun on(event: UserAddedEvent) {
    this.users.add(User(event.gameUserId, event.username))
  }

  @EventSourcingHandler
  fun on(event: UserRemovedEvent) {
    this.users.removeIf { it.gameUserId == event.gameUserId }
  }

  @EventSourcingHandler
  fun on(event: GameCreatedEvent) {
    this.gameId = event.gameId
    this.config = event.config;
    this.moderatorUsername = event.moderatorUsername
    this.gameStatus = GameStatus.CREATED
    this.questionList = event.questionList
  }

  @EventSourcingHandler
  fun on(event: GameStartedEvent) {
    this.gameStatus = GameStatus.STARTED
  }

  @EventSourcingHandler
  fun on(event: GameEndedEvent) {
    this.gameStatus = GameStatus.ENDED
  }

  @EventSourcingHandler
  fun on(event: GameCanceledEvent) {
    this.gameStatus = GameStatus.CANCELED
  }

  @EventSourcingHandler
  fun on(event: QuestionAskedEvent) {
    this.questionsAsked++
    this.askedQuestions.add(
      GameQuestion(
        gameId = gameId,
        id = event.gameQuestionId,
        number = event.gameQuestionNumber,
        questionMode = event.questionMode,
        question = event.question,
        userAnswers = mutableListOf(),
        userBuzzes = mutableListOf(),
        isModerated = moderatorUsername != null,
      )
    )
  }

  @DeadlineHandler(deadlineName = Deadline.QUESTION_CLOSE)
  fun onQuestionClosedDeadline() {
    logger.info { "Reached question deadline for game $gameId" }
    askedQuestions.firstOrNull { !it.isClosed() }?.closeQuestion()
  }

  @DeadlineHandler(deadlineName = Deadline.QUESTION_BUZZER)
  fun onQuestionBuzzDeadline() {
    logger.info { "Reached question buzzer deadline for game $gameId" }
    askedQuestions.firstOrNull { !it.isClosed() }?.evaluateBuzzes()
  }

  @DeadlineHandler(deadlineName = Deadline.GAME_ABANDONED)
  fun onGameAbandonedDeadline() {
    logger.info { "Reached game abandon deadline for game $gameId" }

    if (this.gameStatus == GameStatus.CANCELED || this.gameStatus == GameStatus.ENDED) {
      logger.warn { "${Deadline.GAME_ABANDONED} triggered for ${this.gameId} but game is already in status ${this.gameStatus}" }
    } else {
      AggregateLifecycle.apply(
        GameCanceledEvent(gameId)
      )
    }
  }

  @ExceptionHandler(resultType = GameProblem::class, messageType = Message::class, payloadType = Any::class)
  fun onException(ex: GameProblem) {
    throw CommandExecutionException(ex.message, ex, mapOf(
      "type" to ex.type,
      "title" to ex.title,
      "detail" to ex.detail,
      "category" to ex.category,
      "context" to (ex.context?: emptyMap()) + mapOf("aggregateId" to ex.gameId)
    ))
  }

  private fun askNextQuestion(questionPort: QuestionPort, deadlineScheduler: DeadlineScheduler) {
    val question = questionPort.getQuestion(questionList[askedQuestions.size])

    val questionMode = if (this.config.useBuzzer) GameQuestionMode.BUZZER else GameQuestionMode.COLLECTIVE

    AggregateLifecycle.apply(
      QuestionAskedEvent(
        gameId = gameId,
        gameQuestionId = UUID.randomUUID(),
        gameQuestionNumber = askedQuestions.size + 1,
        questionTimestamp = Instant.now(),
        questionMode = questionMode,
        timeToAnswer = config.secondsToAnswer * 1000,
        question = question
      )
    )

    if (questionMode != GameQuestionMode.BUZZER && config.secondsToAnswer > 0) {
      deadlineScheduler.schedule(Duration.ofSeconds(config.secondsToAnswer), Deadline.QUESTION_CLOSE, this.gameId)
    }

    deadlineScheduler.cancel(Deadline.GAME_ABANDONED, this.gameId)
    deadlineScheduler.schedule(Duration.ofMinutes(15), Deadline.GAME_ABANDONED, this.gameId)
  }

  private fun endGame(deadlineScheduler: DeadlineScheduler) {
    AggregateLifecycle.apply(
      GameEndedEvent(
        gameId = gameId
      )
    )
    deadlineScheduler.cancel(Deadline.GAME_ABANDONED, this.gameId)
  }

  fun MutableList<GameQuestion>.getById(gameQuestionId: UUID): GameQuestion {
    return this.find { it.id == gameQuestionId } ?: throw QuestionNotFoundProblem(gameId, gameQuestionId)
  }

  fun MutableList<User>.findByUsername(username: String): User? {
    return this.find { it.username == username }
  }

  fun MutableList<User>.getByUsername(username: String): User {
    return this.find { it.username == username } ?: throw UserNotFoundProblem(gameId, username)
  }

  fun MutableList<User>.containsUsername(username: String): Boolean {
    return this.find { it.username == username } != null
  }
}

data class User(
  val gameUserId: UUID,
  val username: String,
)

enum class GameStatus {
  CREATED,
  STARTED,
  ENDED,
  CANCELED
}
