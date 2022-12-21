package io.github.petercrawley.aggregator

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import io.github.petercrawley.aggregator.commands.HelpCommand
import io.github.petercrawley.aggregator.commands.RedirectCommand
import io.github.petercrawley.aggregator.commands.UptimeCommand
import io.github.petercrawley.aggregator.database.TargetConfiguration
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import net.dv8tion.jda.api.requests.GatewayIntent
import org.litote.kmongo.*
import org.litote.kmongo.KMongo.createClient
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.exitProcess

object Aggregator : ListenerAdapter() {
	val startTime = System.currentTimeMillis()

	private fun getVariable(name: String): String {
		val variable = System.getenv()[name]

		if (variable == null) {
			System.err.println("Environment variable \"$name\" is missing.")
			exitProcess(1)
		}

		return variable
	}

	private val token = getVariable("DISCORD_TOKEN")
	private val connectionString = getVariable("MONGO_URI")
	private val databaseName = getVariable("MONGO_DATABASE")

	private val jda = JDABuilder.createLight(token)
		.setEnabledIntents(listOf(
			GatewayIntent.MESSAGE_CONTENT,
			GatewayIntent.GUILD_MESSAGES
		))
		.addEventListeners(this)
		.build()

	private val executionDataMap = mutableMapOf<String, (SlashCommandInteractionEvent) -> Unit>()

	private val mongo = createClient(
		MongoClientSettings
			.builder()
			.applyConnectionString(ConnectionString(connectionString))
			.build()
	)

	private val database = mongo.getDatabase(databaseName)

	val targetConfiguration = database.getCollection<TargetConfiguration>().apply {
		ensureIndex(TargetConfiguration::server)
	}

	@JvmStatic
	fun main(vararg arguments: String) {
		// JDA Command Initialisation
		val commandDataList = mutableListOf<SlashCommandData>()

		fun registerCommand(commandClass: CommandClass) {
			val (commandData, executionData) = commandClass.constructCommandData()
			executionDataMap.putAll(executionData)
			commandDataList.add(commandData)
		}

		registerCommand(HelpCommand())
		registerCommand(RedirectCommand())
		registerCommand(UptimeCommand())

		jda.updateCommands().addCommands(commandDataList).queue()

		// Ensure clean shutdowns
		Runtime.getRuntime().addShutdownHook(Thread { stop() })
	}

	private fun stop() {
		jda.shutdown()
		mongo.close()
	}

	override fun onMessageReceived(event: MessageReceivedEvent) {
		if (!event.message.flags.contains(Message.MessageFlag.IS_CROSSPOST)) return // Only allow "crosspost" messages.

		val configurations = targetConfiguration.find(TargetConfiguration::sourceChannels contains event.channel.idLong)

		for (configuration in configurations) {
			val targetChannel = event.guild.getTextChannelById(configuration.targetChannel) ?: continue

			val webhooks = targetChannel.retrieveWebhooks().complete()
			val webhook = webhooks.find { it.ownerAsUser?.idLong == jda.selfUser.idLong } ?: targetChannel.createWebhook("Aggregator Target").complete()

			val connection = URL(webhook.url).openConnection() as HttpURLConnection
			connection.requestMethod = "POST"
			connection.setRequestProperty("Content-Type", "application/json")
			connection.doOutput = true

			val stream = connection.outputStream
			stream.write("{\"username\":\"${event.author.name}\",\"avatar_url\":\"${event.author.avatarUrl}\",\"content\":\"${event.message.contentRaw}\"}".toByteArray())
			stream.flush()
			stream.close()

			connection.responseCode

			connection.disconnect()
		}
	}

	override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
		executionDataMap[event.fullCommandName]!!.invoke(event)
	}
}