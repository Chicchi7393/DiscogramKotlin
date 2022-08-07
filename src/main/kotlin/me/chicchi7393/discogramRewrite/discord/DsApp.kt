package me.chicchi7393.discogramRewrite.discord

import it.tdlight.jni.TdApi.Chat
import me.chicchi7393.discogramRewrite.JsonReader
import me.chicchi7393.discogramRewrite.mongoDB.DatabaseManager
import me.chicchi7393.discogramRewrite.objects.databaseObjects.TicketDocument
import me.chicchi7393.discogramRewrite.objects.databaseObjects.TicketState
import me.chicchi7393.discogramRewrite.telegram.TgApp
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.components.ActionRow
import net.dv8tion.jda.api.interactions.components.buttons.Button
import net.dv8tion.jda.api.requests.restaction.MessageAction
import java.awt.Color
import java.io.File
import java.net.URI

class DsApp private constructor() {
    private val settings = JsonReader().readJsonSettings("settings")!!
    private val dbMan = DatabaseManager.instance
    private val tgApp = TgApp.instance

    init {
        println("DsApp Class Initialized")
    }

    private object GetInstance {
        val INSTANCE = DsApp()
    }

    companion object {
        val instance: DsApp by lazy { GetInstance.INSTANCE }
    }

    lateinit var dsClient: JDA

    fun createApp(): JDA {
        dsClient = JDABuilder.createDefault(settings.discord["token"] as String)
            .setActivity(Activity.watching("i ban degli underage"))
            .addEventListeners(EventHandler())
            .build()
        return dsClient
    }

    fun generateTicketEmbed(
        authorName: String,
        authorUrl: String,
        message: String,
        isForced: Boolean,
        isAssigned: Boolean,
        assignedTo: String = "",
        footerStr: String,
        state: Any
    ): MessageEmbed {
        return EmbedBuilder()
            .setColor(Color.blue)
            .setTitle("Nuovo ticket!")
            .setAuthor(authorName, authorUrl, "attachment://pic.png")
            .setDescription(message)
            .addField("Forzato?", if (isForced) "Sì" else "No", true)
            .addField("Assegnato a", if (isAssigned) assignedTo else "Nessuno", true)
            .addField("Stato", state.toString(), false)
            .setFooter(footerStr, null)
            .build()
    }

    fun generateFirstEmbedButtons(tg_profile: String = "https://google.com"): ActionRow {
        return ActionRow.of(
            listOf(
                Button.link(tg_profile, "Apri profilo Telegram")
            )
        )
    }

    fun generateSecondEmbedButtons(channel_id: Long): ActionRow {
        return ActionRow.of(
            listOf(
                Button.success("assign-$channel_id", "Assegna"),
                Button.secondary("suspend-$channel_id", "Sospendi"),
                Button.danger("close-$channel_id", "Chiudi"),
            )
        )
    }

    fun getLastModified(directoryFilePath: String): File? {
        val directory = File(directoryFilePath)
        val files = directory.listFiles { obj: File -> obj.isFile }
        var lastModifiedTime = Long.MIN_VALUE
        var chosenFile: File? = null
        if (files != null) {
            for (file in files) {
                if (file.lastModified() > lastModifiedTime) {
                    chosenFile = file
                    lastModifiedTime = file.lastModified()
                }
            }
        }
        return chosenFile
    }

    fun getGuild(): Guild {
        return dsClient.getGuildById(settings.discord["guild_id"] as Long)!!
    }

    fun sendTextMessageToChannel(channel: Long, text: String, reply_id: Long, ticket_id: Int): MessageAction {
        var ds_reply = 0L
        if (reply_id != 0L) {
            ds_reply = dbMan.Search().MessageLinks().searchDsMessageByTelegramMessage(ticket_id, reply_id)
        }
        return dsClient.getThreadChannelById(
            channel
        )!!.sendMessage(text).referenceById(ds_reply)
    }

    fun createTicket(chat: Chat, message: String) {
        val pfpId = try {
            chat.photo.small.id
        } catch (_: NullPointerException) {
            69420
        }
        tgApp.downloadFile(pfpId)
        Thread.sleep(500)
        val filePath =
            if (pfpId != 69420) getLastModified("session/database/profile_photos")!!.absolutePath else File("./session/database/profile_photos/5900.jpg").absolutePath
        val embed = generateTicketEmbed(
            chat.title,
            "https://chicchi7393.xyz/redirectTg.html?id=${chat.id}",
            message,
            isForced = false,
            isAssigned = false,
            footerStr = "${settings.discord["IDPrefix"]}${dbMan.Utils().getLastUsedTicketId() + 1}",
            state = TicketState.OPEN
        )
        dsClient
            .getChannelById(MessageChannel::class.java, settings.discord["channel_id"] as Long)!!
            .sendMessageEmbeds(
                embed
            ).addFile(File(URI("file://${filePath}")), "pic.png").map {
                it.editMessageEmbeds(
                    embed
                ).setActionRows(
                    generateFirstEmbedButtons(
                        "https://chicchi7393.xyz/redirectTg.html?id=${chat.id}"
                    ),
                    generateSecondEmbedButtons(it.idLong),
                    ActionRow.of(
                        Button.primary("menu-${it.id}", "Apri menu")
                    )
                ).queue()
                it.createThreadChannel(
                    "${settings.discord["IDPrefix"]}${dbMan.Utils().getLastUsedTicketId() + 1}"
                ).map { tIt ->
                    dbMan.Create().Tickets().createTicketDocument(
                        TicketDocument(
                            chat.id,
                            tIt.idLong,
                            dbMan.Utils().getLastUsedTicketId() + 1,
                            mapOf("open" to true, "suspended" to false, "closed" to false),
                            System.currentTimeMillis() / 1000
                        )
                    )
                }.queue()
            }
            .queue()
    }

    fun isHigherRole(member: Member): Boolean {
        var isHigher = false
        for (role in member.roles) {
            if (role.idLong in settings.discord["higher_roles"] as List<Long>) {
                isHigher = true
            }
        }
        return isHigher
    }

    fun createCommands() {
        dsClient.updateCommands().addCommands(
            Commands.slash("tickets", "Ti da una lista di vecchi ticket di un utente")
                .addOption(OptionType.STRING, "username", "L'username/ID dell'utente a cui controllare i ticket", true),
            Commands.slash("cronologia", "Leggi la cronologia dei messaggi")
                .addOption(OptionType.STRING, "username", "L'username/ID dell'utente a cui controllare i ticket", true)
                .addOption(OptionType.INTEGER, "messaggi", "Numero messaggi (10 se non messo)", false),
            Commands.slash("block", "Blocca utente")
                .addOption(
                    OptionType.STRING,
                    "username",
                    "L'username/ID dell'utente da bloccare (utente nel thread se non specificato)",
                    false
                ),
            Commands.slash("unblock", "Sblocca utente")
                .addOption(
                    OptionType.STRING,
                    "username",
                    "L'username/ID dell'utente da sbloccare (utente nel thread se non specificato)",
                    false
                )

        ).queue()
    }

}