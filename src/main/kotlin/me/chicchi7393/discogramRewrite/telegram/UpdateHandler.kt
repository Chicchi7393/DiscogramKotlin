package me.chicchi7393.discogramRewrite.telegram

import it.tdlight.client.SimpleTelegramClient
import it.tdlight.jni.TdApi.*
import me.chicchi7393.discogramRewrite.JsonReader
import me.chicchi7393.discogramRewrite.handlers.ticketHandlers
import me.chicchi7393.discogramRewrite.mongoDB.DatabaseManager
import me.chicchi7393.discogramRewrite.telegram.utils.FindContent
import java.lang.NullPointerException


class UpdateHandler(private val tgClient: SimpleTelegramClient) {
    private val settings = JsonReader().readJsonSettings("settings")!!
    private val messageTable = JsonReader().readJsonMessageTable("messageTable")!!
    private val ticketHandlers = ticketHandlers()
    private val dbMan = DatabaseManager.instance
    fun authStateUpdate(update: UpdateAuthorizationState) {
        println(
            when (update.authorizationState) {
                is AuthorizationStateReady -> messageTable.generalStrings["log_loggedIn"]
                is AuthorizationStateLoggingOut -> messageTable.generalStrings["log_loggingOut"]
                is AuthorizationStateClosing -> messageTable.generalStrings["log_closing"]
                is AuthorizationStateClosed -> messageTable.generalStrings["log_closed"]
                else -> ""
            }
        )
    }

    private fun ticketIfList(chat: Chat, message: Message): Boolean {
        return (chat.type is ChatTypePrivate
                && chat.id !in settings.discord["ignoreTGAuthor"] as List<Long>
                && (message.senderId as MessageSenderUser).userId != (settings.telegram["userbotID"] as Number).toLong())
    }

    fun onUpdateNewMessage(update: UpdateNewMessage): Any {
        val findContentClass = FindContent(update.message)
        val text = findContentClass.findText()
        val document = findContentClass.findData()
        if (((System.currentTimeMillis()/1000)-update.message.date) > 30 ) {return false}
        tgClient.send(GetChat(update.message.chatId)) {
            val chat = it.get()
            if (update.message.content is MessageText) {
                if (ticketIfList(chat, update.message)) {
                    if (dbMan.Utils().searchAlreadyOpen(chat.id) != null || dbMan.Utils()
                            .searchAlreadySuspended(chat.id) != null
                    )
                        ticketHandlers.sendTextFollowMessage(
                            chat.id,
                            text,
                            dbMan.Utils().searchAlreadySuspended(chat.id) != null,
                            dbMan.Search().Tickets()
                                .searchTicketDocumentByTelegramId((update.message.senderId as MessageSenderUser).userId)!!.ticketId,
                            update.message.id,
                            update.message.replyToMessageId
                        )
                    else
                        ticketHandlers.startTicketWithText(chat, text)
                }
            } else {
                val file: DownloadFile? = if (document != 0) {
                    DownloadFile(document, 1, 0, 0, true)
                } else {
                    null
                }
                if (ticketIfList(chat, update.message)) {
                    if (dbMan.Utils().searchAlreadyOpen(chat.id) == null && dbMan.Utils()
                            .searchAlreadySuspended(chat.id) == null
                    )
                        ticketHandlers.startTicketWithFile(
                            chat,
                            file,
                            text
                        )
                    else
                        ticketHandlers.sendFileFollowMessage(
                            chat.id,
                            file,
                            text,
                            dbMan.Utils().searchAlreadySuspended(chat.id) != null,
                            dbMan.Search().Tickets()
                                .searchTicketDocumentByTelegramId((update.message.senderId as MessageSenderUser).userId)!!.ticketId,
                            update.message.id,
                            update.message.replyToMessageId
                        )
                }
            }
        }
        return true
    }

    fun onUpdateMessageSendSucceeded(update: UpdateMessageSendSucceeded) {
        try {
            dbMan.Update().MessageLinks().updateMessageId(
                dbMan.Search().Tickets().searchTicketDocumentByTelegramId(update.message.chatId)!!.ticketId,
                update.oldMessageId,
                update.message.id
            )
        } catch (_: NullPointerException) {}
    }
}