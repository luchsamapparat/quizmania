package org.quizmania.rest.adapter.out

import jakarta.persistence.*
import org.quizmania.game.api.GameId
import org.quizmania.rest.application.domain.Game
import org.quizmania.rest.application.domain.GameStatus
import org.quizmania.rest.application.domain.GameUser
import org.quizmania.rest.port.out.GameRepository
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import java.util.*

@Component
class GameJPARepositoryAdapter(
  val repository: GameJPARepository
) : GameRepository {
  override fun save(game: Game) {
    repository.save(GameEntity.fromModel(game))
  }

  override fun findById(gameId: GameId): Game? {
    return repository.findByIdOrNull(gameId)?.toModel()
  }

  override fun findAll(): List<Game> {
    return repository.findAll().map { it.toModel() } .toList()
  }

  override fun findByStatus(status: GameStatus): List<Game> {
    return repository.findByStatus(status).map { it.toModel() }
  }
}

interface GameJPARepository : CrudRepository<GameEntity, GameId> {
  fun findByStatus(status: GameStatus): List<GameEntity>
}

@Entity(name = "GAME")
class GameEntity(
  @Id
  val gameId: UUID,
  var name: String,
  var maxPlayers: Int,
  var numQuestions: Int,
  var creator: String,
  var moderator: String?,

  var questionTimeout: Long,
  @Enumerated(EnumType.STRING)
  var status: GameStatus,

  @OneToMany(cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
  @JoinColumn(name = "game_id")
  var users: MutableList<GameUserEntity> = mutableListOf(),
) {

  companion object {
    fun fromModel(model: Game): GameEntity {
      return GameEntity(
        gameId = model.gameId,
        name = model.name,
        maxPlayers = model.maxPlayers,
        numQuestions = model.numQuestions,
        creator = model.creator,
        moderator = model.moderator,
        questionTimeout = model.questionTimeout,
        status = model.status,
        users = model.users.map { GameUserEntity.fromModel(it) }.toMutableList() // mutability needed for JPA?
      )
    }
  }

  fun toModel(): Game = Game(
    gameId = this.gameId,
    name = this.name,
    maxPlayers = this.maxPlayers,
    numQuestions = this.numQuestions,
    creator = this.creator,
    moderator = this.moderator,
    questionTimeout = this.questionTimeout,
    status = this.status,
    users = this.users.map { it.toModel() }.toMutableList()
  )
}

@Entity(name = "GAME_USER")
class GameUserEntity(
  @Id
  val gameUserId: UUID,
  var username: String,
) {

  companion object {
    fun fromModel(model: GameUser): GameUserEntity {
      return GameUserEntity(
        gameUserId = model.gameUserId,
        username = model.username,
      )
    }
  }

  fun toModel(): GameUser = GameUser(
    gameUserId = this.gameUserId,
    username = this.username,
  )
}
