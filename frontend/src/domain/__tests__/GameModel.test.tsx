import {Game, GameStatus} from '../GameModel'
import {
  GameCreatedEvent,
  GameStartedEvent,
  QuestionAnsweredEvent, QuestionAnswerOverriddenEvent,
  QuestionAskedEvent, QuestionClosedEvent, QuestionRatedEvent,
  QuestionType,
  UserAddedEvent,
  UserRemovedEvent
} from '../../services/GameEventTypes'

describe('testing game read model', () => {
  test('newly created game should have status CREATED', () => {
    let event: GameCreatedEvent = gameCreatedEvent()
    let game = new Game(event)

    expect(game.status).toBe(GameStatus.CREATED);
  });

  test('started game should have status STARTED', () => {
    let event: GameCreatedEvent = gameCreatedEvent()
    let game = new Game(event)

    game = game.onGameStarted(gameStartedEvent())

    expect(game.status).toBe(GameStatus.STARTED);
  });

  test('added players should appear in player list', () => {
    let game = new Game(gameCreatedEvent())

    game = game.onPlayerAdded(playerAddedEvent("player2", "Player 2"))
    game = game.onPlayerAdded(playerAddedEvent("player3", "Player 3"))
    game = game.onPlayerAdded(playerAddedEvent("player4", "Player 4"))

    expect(game.players.length).toBe(3);
    expect(game.players.find(p => p.id == "player2")).toBeDefined()
    expect(game.players.find(p => p.id == "player3")).toBeDefined()
    expect(game.players.find(p => p.id == "player4")).toBeDefined()
  });

  test('removed player should not appear in filled user list', () => {
    let game = new Game(gameCreatedEvent())

    game = game.onPlayerAdded(playerAddedEvent("player2", "Player 2"))
    game = game.onPlayerAdded(playerAddedEvent("player3", "Player 3"))
    game = game.onPlayerAdded(playerAddedEvent("player4", "Player 4"))

    game = game.onPlayerRemoved(playerRemovedEvent("player2", "Player 2"))

    expect(game.players.length).toBe(2);
    expect(game.players.find(p => p.id == "player2")).not.toBeDefined()
    expect(game.players.find(p => p.id == "player3")).toBeDefined()
    expect(game.players.find(p => p.id == "player4")).toBeDefined()
  });

  test('asked question should appear in question list', () => {
    let game = new Game(gameCreatedEvent())
    game = game.onPlayerAdded(playerAddedEvent("player2", "Player 2"))

    game = game.onQuestionAsked(questionAsked("question1", 1, "Foo1", "Bar1"))
    game = game.onQuestionAsked(questionAsked("question2", 2, "Foo2", "Bar2"))
    game = game.onQuestionAsked(questionAsked("question3", 3, "Foo3", "Bar3"))

    expect(game.questions.length).toBe(3);

    let question = game.questions.find(q => q.gameQuestionId == "question3")
    expect(question).toBeDefined()
    expect(question!.question.phrase).toBe("Foo3");
  });

  test('question can be answered', () => {
    let game = new Game(gameCreatedEvent())
    game = game.onPlayerAdded(playerAddedEvent("player2", "Player 2"))
    game = game.onQuestionAsked(questionAsked("question1", 1, "Foo1", "Bar1"))

    game = game.onQuestionAnswered(questionAnswered("question1", "player2", "answer01_02", "bla"))

    expect(game.questions.length).toBe(1);
    expect(game.questions[0].answers.length).toBe(1);

    let question = game.questions.find(q => q.gameQuestionId == "question1")
    expect(question!.answers).toHaveLength(1)
    expect(question!.answers[0].answer).toBe("bla");
  });

  test('question can be rated', () => {
    let game = new Game(gameCreatedEvent())
    game = game.onPlayerAdded(playerAddedEvent("player2", "Player 2"))
    game = game.onPlayerAdded(playerAddedEvent("player3", "Player 3"))
    game = game.onQuestionAsked(questionAsked("question1", 1, "Foo1", "Bar1"))

    game = game.onQuestionAnswered(questionAnswered("question1", "player2", "answer01_02", "bla"))
    game = game.onQuestionAnswered(questionAnswered("question1", "player3", "answer01_03", "foo"))

    game = game.onQuestionRated(questionRated("question1", {
        "player2": 10
      }
    ));

    expect(game.questions[0].answers.length).toBe(2);
    expect(game.findPlayerPoints("player2")).toBe(10)
    expect(game.findPlayerPoints("player3")).toBe(0)
  });
});

function gameCreatedEvent(): GameCreatedEvent {
  return {
    gameId: "game1",
    name: "Game 1",
    config: {
      maxPlayers: 10,
      numQuestions: 10,
      secondsToAnswer: 10,
      questionSetId: "questionSet1"
    },
    creatorUsername: "player1",
    moderatorUsername: undefined,
  }
}

function gameStartedEvent(): GameStartedEvent {
  return {
    gameId: "game1",
  }
}

function playerAddedEvent(id: string, name: string): UserAddedEvent {
  return {
    gameId: "game1",
    gameUserId: id,
    username: name,
  }
}

function playerRemovedEvent(id: string, name: string): UserRemovedEvent {
  return {
    gameId: "game1",
    gameUserId: id,
    username: name,
  }
}

function questionAsked(id: string, number: number, phrase: string, answer: string): QuestionAskedEvent {
  return {
    gameId: "game1",
    gameQuestionId: id,
    gameQuestionNumber: number,
    questionTimestamp: new Date().toISOString(),
    timeToAnswer: 10000,
    question: {
      type: QuestionType.FREE_INPUT,
      phrase: phrase,
      imagePath: undefined,
      correctAnswer: answer,
      answerOptions: []
    },
  }
}

function questionAnswered(id: string, playerId: string, answerId: string, answer: string): QuestionAnsweredEvent {
  return {
    gameId: "game1",
    gameQuestionId: id,
    gameUserId: playerId,
    userAnswerId: answerId,
    answer: answer,
  }
}

function questionAnswerOverridden(id: string, playerId: string, answerId: string, answer: string): QuestionAnswerOverriddenEvent {
  return {
    gameId: "game1",
    gameQuestionId: id,
    gameUserId: playerId,
    userAnswerId: answerId,
    answer: answer,
  }
}

function questionClosed(id: string): QuestionClosedEvent {
  return {
    gameId: "game1",
    gameQuestionId: id,
  }
}

function questionRated(id: string, points: { [key: string]: number }): QuestionRatedEvent {
  return {
    gameId: "game1",
    gameQuestionId: id,
    points: points,
  }
}
