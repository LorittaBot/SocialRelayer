package net.perfectdreams.loritta.socialrelayer.common.utils

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.perfectdreams.loritta.socialrelayer.common.config.LorittaDatabaseConfig
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.sql.Connection

object DatabaseUtils {
    /**
     * Connects to Loritta's PostgreSQL database, using the credentials from the [config] object.
     *
     * @param config the database credentials
     * @return Exposed's database connection
     */
    fun connectToDatabase(config: LorittaDatabaseConfig): Database {
        val hikariConfig = HikariConfig()
        hikariConfig.jdbcUrl = "jdbc:postgresql://${config.address}/${config.databaseName}"
        hikariConfig.username = config.username
        hikariConfig.password = config.password

        // https://github.com/JetBrains/Exposed/wiki/DSL#batch-insert
        hikariConfig.addDataSourceProperty("reWriteBatchedInserts", "true")

        // Exposed uses autoCommit = false, so we need to set this to false to avoid HikariCP resetting the connection to
        // autoCommit = true when the transaction goes back to the pool, because resetting this has a "big performance impact"
        // https://stackoverflow.com/a/41206003/7271796
        hikariConfig.isAutoCommit = false

        // Useful to check if a connection is not returning to the pool, will be shown in the log as "Apparent connection leak detected"
        hikariConfig.leakDetectionThreshold = 30 * 1000

        // We need to use the same transaction isolation used in Exposed, in this case, TRANSACTION_READ_COMMITED.
        // If not HikariCP will keep resetting to the default when returning to the pool, causing performance issues.
        hikariConfig.transactionIsolation = "TRANSACTION_REPEATABLE_READ"

        val ds = HikariDataSource(hikariConfig)
        val database = Database.connect(ds)
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_REPEATABLE_READ

        return database
    }
}