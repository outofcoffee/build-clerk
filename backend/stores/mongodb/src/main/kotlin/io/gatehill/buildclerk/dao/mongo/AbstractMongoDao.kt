package io.gatehill.buildclerk.dao.mongo

import com.mongodb.MongoCredential
import com.mongodb.ServerAddress
import com.mongodb.client.MongoCollection
import io.gatehill.buildclerk.api.Recorded
import io.gatehill.buildclerk.dao.mongo.config.MongoSettings
import io.gatehill.buildclerk.dao.mongo.model.Dated
import org.litote.kmongo.KMongo
import org.litote.kmongo.ascending
import org.litote.kmongo.descending
import org.litote.kmongo.getCollection
import java.time.ZonedDateTime

abstract class AbstractMongoDao : Recorded {
    abstract val collectionName: String

    /**
     * Execute `block` on a Mongo collection and close the client after use.
     */
    protected inline fun <reified T : Any, R> withCollection(
        block: MongoCollection<T>.() -> R
    ): R = KMongo.createClient(
        addr = ServerAddress(MongoSettings.host, MongoSettings.port),
        credentialsList = buildMongoCredentials()
    ).use { client ->
        val database = client.getDatabase(databaseName)
        val collection = database.getCollection<T>(collectionName)
        collection.block()
    }

    protected inline fun <reified T : Any> count() = withCollection<T, Int> {
        countDocuments().toInt()
    }

    protected inline fun <reified T : Dated> oldestDate() = withCollection<T, ZonedDateTime?> {
        find()
            .sort(ascending(Dated::createdDate))
            .limit(1)
            .firstOrNull()
            ?.createdDate
    }

    protected inline fun <reified T : Dated> newestDate() = withCollection<T, ZonedDateTime?> {
        find()
            .sort(descending(Dated::createdDate))
            .limit(1)
            .firstOrNull()
            ?.createdDate
    }

    companion object {
        protected const val databaseName = "clerk"

        protected fun buildMongoCredentials(): List<MongoCredential> = MongoSettings.userName?.let { userName ->
            listOf(
                MongoCredential.createCredential(
                    userName,
                    databaseName,
                    MongoSettings.password.toCharArray()
                )
            )
        } ?: emptyList()
    }
}
