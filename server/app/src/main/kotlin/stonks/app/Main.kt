package stonks.app

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import stonks.app.db.Db

fun main() {
    val config = AppConfig.fromEnv()
    val db = Db.connect(config.dbUrl, config.dbUser, config.dbPassword)
    val app = App(config, db)
    app.start()
    Runtime.getRuntime().addShutdownHook(Thread { app.close() })
    embeddedServer(Netty, port = config.port) { stonksModule(app) }.start(wait = true)
}
