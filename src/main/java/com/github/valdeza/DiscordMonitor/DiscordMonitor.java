package com.github.valdeza.DiscordMonitor;

import java.io.File;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import javax.security.auth.login.LoginException;

import com.github.valdeza.DiscordMonitor.DiscordMonitorTargetIdentifier.MessageProcessingOptions;

import net.dv8tion.jda.client.entities.Group;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.JDABuilder;
import net.dv8tion.jda.core.entities.ChannelType;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.Member;
import net.dv8tion.jda.core.entities.Message;
import net.dv8tion.jda.core.entities.Message.Attachment;
import net.dv8tion.jda.core.entities.MessageEmbed;
import net.dv8tion.jda.core.entities.PrivateChannel;
import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.entities.User;
import net.dv8tion.jda.core.events.ReadyEvent;
import net.dv8tion.jda.core.events.message.GenericMessageEvent;
import net.dv8tion.jda.core.events.message.MessageBulkDeleteEvent;
import net.dv8tion.jda.core.events.message.MessageDeleteEvent;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;
import net.dv8tion.jda.core.events.message.MessageUpdateEvent;
import net.dv8tion.jda.core.exceptions.RateLimitedException;
import net.dv8tion.jda.core.hooks.ListenerAdapter;

class DiscordMonitor
{
	private static final DateTimeFormatter LOG_DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSXXXXX");
	private static final int ATTACHMENT_DOWNLOAD_RETRY_LIMIT = 5;

	private DiscordMonitorConfig appconfig;

	public DiscordMonitor(DiscordMonitorConfig appconfig)
	{
		this.appconfig = appconfig;
	}

	public void start()
	{
		// TODO Check if .sqlite exists. If not, initialise one.

		try
		{
			new JDABuilder(this.appconfig.authType)
				.setToken(this.appconfig.authToken)
				.setAudioEnabled(false)
				.setAutoReconnect(true)
				.addEventListener(new DiscordMonitorListenerAdapterPrep())
				.buildBlocking(); // TODO use .buildAsync()?
		}
		catch (LoginException | IllegalArgumentException e)
		{
			System.err.println("error: Log in failed. Please check provided token.");
		}
		catch (RateLimitedException e)
		{
			System.err.println("error: This application is being rate limited by Discord.");
		}
		catch (InterruptedException e)
		{
			System.err.println("fatal: Unexpected interrupt.");
			e.printStackTrace();
		}
	}

	private class DiscordMonitorListenerAdapterPrep extends ListenerAdapter
	{
		@Override
		public void onReady(ReadyEvent event)
		{
			System.out.println("info: Validating configuration against JDA instance...");
			JDA jda = event.getJDA();
			DiscordMonitor.this.appconfig.validateJDA(jda);
			jda.removeEventListener(this);
			jda.addEventListener(new DiscordMonitorListenerAdapter());
			System.out.println("info: Validation complete. Discord events subscribed to.");
		}
	}

	private class DiscordMonitorListenerAdapter extends ListenerAdapter
	{
		@Override
		public void onMessageReceived(MessageReceivedEvent event)
		{
			boolean authorizedCommandHit = false;
			if (DiscordMonitor.this.appconfig.commandPrefix == null || event.getMessage().getRawContent().startsWith(DiscordMonitor.this.appconfig.commandPrefix))
			{ // Command detected
				for (DiscordMonitorTargetIdentifier targetid : DiscordMonitor.this.appconfig.authorizedUsers)
				{
					if (!DiscordMonitorBotUtilities.isTargetIdentifierMatchGeneric(targetid, event))
						continue;

					// Command authorised
					authorizedCommandHit = true;
					User author = event.getAuthor();
					if (event.getChannelType().isGuild() && !event.getMember().getEffectiveName().equals(author.getName()))
					{ // The authour has a nickname.
						System.out.printf("*** Command ran by user '%s#%s' (nickname: '%s', id: %d) as per identifier '%s':\n\t%s\n\n",
							author.getName(), author.getDiscriminator(), event.getMember().getEffectiveName(), author.getIdLong(), targetid.identifierLabel, event.getMessage().getRawContent());
					}
					else
					{
						System.out.printf("*** Command ran by user '%s#%s' (id: %d) as per identifier '%s':\n\t%s\n\n",
							author.getName(), author.getDiscriminator(), author.getIdLong(), targetid.identifierLabel, event.getMessage().getRawContent());
					}
					break;
				}
			}
			if (authorizedCommandHit)
				; //TODO Process and log command
			else // Proceed with treating this as a potentially loggable event.
				eventHandlerGenericMessageEvent(event);
		}

		@Override
		public void onMessageUpdate(MessageUpdateEvent event)
		{
			eventHandlerGenericMessageEvent(event);
		}

		@Override
		public void onMessageDelete(MessageDeleteEvent event)
		{
			eventHandlerGenericMessageEvent(event);
		}

		/**
		 * Checks log and notification targets and prints message content to console for the following event types:
		 * <ul>
		 * <li> {@link net.dv8tion.jda.core.events.message.MessageDeleteEvent}
		 * <li> {@link net.dv8tion.jda.core.events.message.MessageReceivedEvent}
		 * <li> {@link net.dv8tion.jda.core.events.message.MessageUpdateEvent}
		 * </ul>
		 * @param event
		 * @throws IllegalArgumentException Thrown if provided <em>event</em> is not one of the aforementioned types.
		 */
		private void eventHandlerGenericMessageEvent(GenericMessageEvent event)
		{
			//These are provided with every event in JDA
			JDA jda = event.getJDA();                       //JDA, the core of the api.

			boolean doAutoDownloadAttachments = false;

			boolean declaredLoggableHit = false;
			for (DiscordMonitorTargetIdentifier targetid : DiscordMonitor.this.appconfig.logTargets)
			{
				if (!DiscordMonitorBotUtilities.isTargetIdentifierMatchGeneric(targetid, event))
					continue;

				// All conditions passed.
				if (!declaredLoggableHit)
				{
					declaredLoggableHit = true;
					System.out.print("(i) Logging for:");
				}
				System.out.print(" " + targetid.identifierLabel);

				if (!doAutoDownloadAttachments // Skip check if already true
						&& targetid.messageProcessingOptions != null && targetid.messageProcessingOptions.contains(MessageProcessingOptions.AUTODOWNLOAD_ATTACHMENTS))
					doAutoDownloadAttachments = true;
			}
			if (declaredLoggableHit)
				System.out.println();

			boolean declaredNotificationHit = false;
			for (DiscordMonitorTargetIdentifier targetid : DiscordMonitor.this.appconfig.notificationWatchlist)
			{
				if (!DiscordMonitorBotUtilities.isTargetIdentifierMatchGeneric(targetid, event))
					continue;

				// All conditions passed.
				if (!declaredNotificationHit)
				{
					declaredNotificationHit = true;
					System.out.print("/!\\ WATCHLIST HIT:");
				}
				System.out.print(" " + targetid.identifierLabel);

				if (!doAutoDownloadAttachments // Skip check if already true
						&& targetid.messageProcessingOptions != null && targetid.messageProcessingOptions.contains(MessageProcessingOptions.AUTODOWNLOAD_ATTACHMENTS))
					doAutoDownloadAttachments = true;
			}
			if (declaredNotificationHit)
				System.out.println();

			//Event specific information
			Message message = null; //The message that was received.
			if (event instanceof MessageReceivedEvent)
				message = ((MessageReceivedEvent)event).getMessage();
			else if (event instanceof MessageUpdateEvent)
				message = ((MessageUpdateEvent)event).getMessage();
			else if (!(event instanceof MessageDeleteEvent))
				throw new IllegalArgumentException("Provided event is not of type Message(Delete|Received|Update)Event");

			StringBuilder msg = new StringBuilder("\n");
			if (message == null)
			{
				msg.append("Time: ").append(OffsetDateTime.now(ZoneId.of("Z")).format(DiscordMonitor.LOG_DATETIME_FORMAT)).append(" (approximate)")
					.append("\nMESSAGE DELETED");

				//TODO Poll .sqlite db for previous message details

				if (event.isFromType(ChannelType.TEXT))         //If this message was sent to a Guild TextChannel
				{
					TextChannel textChannel = (TextChannel)event.getChannel();
					System.out.printf("%d:(%s)[%s]: %s\n\n", event.getMessageIdLong(), textChannel.getGuild().getName(), textChannel.getName(), msg);
				}
				else if (event.isFromType(ChannelType.PRIVATE))
				{
					PrivateChannel privateChannel = (PrivateChannel)event.getChannel();
					System.out.printf("%d:[DM]<%s>: %s\n\n", event.getMessageIdLong(), privateChannel.getName(), msg);
				}
				else if (event.isFromType(ChannelType.GROUP))   //If this message was sent to a Group. This is CLIENT only!
				{
					//The message was sent in a Group. It should be noted that Groups are CLIENT only.
					Group group = (Group)event.getChannel();
					String groupName = group.getName() != null ? group.getName() : "";  //A group name can be null due to it being unnamed.

					System.out.printf("%d:[GRP: %s]: %s\n\n", event.getMessageIdLong(), groupName, msg);
				}
			}
			else // event includes a Message variable
			{
				msg.append("Time: ").append(
						message.isEdited()
						? message.getEditedTime().format(DiscordMonitor.LOG_DATETIME_FORMAT)
						: message.getCreationTime().format(DiscordMonitor.LOG_DATETIME_FORMAT))
					.append("\nMessage: ").append(message.getContent()); //This returns a human readable version of the Message. Similar to what you would see in the client.
				List<MessageEmbed> embeds = message.getEmbeds();
				int currEmbedCount = 0;
				for (MessageEmbed embedProbe : embeds)
				{
					msg.append(String.format("\nEmbed[%d]: ", currEmbedCount++))
						.append(DiscordMonitorBotUtilities.GSON_MESSAGE_ELEMENT_SERIALISER.toJson(embedProbe));
				}

				boolean attachmentDownloadFailed = false;
				List<Attachment> attachments = message.getAttachments();
				int currAttachmentCount = 0;
				for (Attachment attachmentProbe : attachments)
				{
					msg.append(String.format("\nAttachment[%d]: ", currAttachmentCount++))
						.append(DiscordMonitorBotUtilities.GSON_MESSAGE_ELEMENT_SERIALISER.toJson(attachmentProbe));

					if (doAutoDownloadAttachments)
					{
						if (DiscordMonitor.this.appconfig.attachmentDatastorePaths == null)
						{ // Attachment auto-downloading disabled
							doAutoDownloadAttachments = false;
							continue;
						}

						/* Note: Despite edits being unable to add/remove attachments,
						 * will also download for edited messages because
						 * (1) the bot may not have been active at the time of message creation and
						 * (2) Discord file attachment limits should typically have negligible impact on disk space usage
						 *     (8 MB typical attachment limit; 50 MB for presumably rare Discord Nitro users)
						 */
						// Loop to retry downloads if needed.
						for (int retryCount = 1; retryCount <= DiscordMonitor.ATTACHMENT_DOWNLOAD_RETRY_LIMIT; ++retryCount)
						{
							if (retryCount != 1)
								System.out.printf("info: Attachment download attempt %d/%d", retryCount, DiscordMonitor.ATTACHMENT_DOWNLOAD_RETRY_LIMIT);

							DiscordMonitor.this.appconfig.refreshCurrentAttachmentDatastorePath();
							if (DiscordMonitor.this.appconfig.attachmentDatastorePaths.isEmpty())
							{ // No directory to download to
								attachmentDownloadFailed = true;
								break;
							}

							File downloadPath = DiscordMonitor.this.appconfig.generateDownloadFilepath(attachmentProbe.getFileName());
							if (attachmentProbe.download(downloadPath))
							{ // Download successful
								DiscordMonitor.this.appconfig.notifySpentAttachmentDatastoreCapacity(attachmentProbe.getSize());
								msg.append("\nAttachment downloaded to: ").append(downloadPath.toString());
								break;
							}

							// Download unsuccessful. Test if directory is writable before retrying.
							if (retryCount == 1)
							{
								boolean writeSuccess = false;
								try
								{
									File.createTempFile("writetest", null, DiscordMonitor.this.appconfig.attachmentDatastorePaths.peek()).deleteOnExit();
									writeSuccess = true;
								}
								catch (IOException e)
								{
									System.out.println("warning: Current AttachmentDatastorePath is not able to be written to.");
									System.out.println(e.toString()); // Print details?
									DiscordMonitor.this.appconfig.nextAttachmentDatastorePath(false);
								}
								catch (SecurityException e)
								{
									System.out.println("warning: Denied write access to current AttachmentDatastorePath.");
									DiscordMonitor.this.appconfig.nextAttachmentDatastorePath(false);
								}

								if (!writeSuccess)
								{
									attachmentDownloadFailed = true;
									break;
								}
							}

							if (retryCount == DiscordMonitor.ATTACHMENT_DOWNLOAD_RETRY_LIMIT)
								attachmentDownloadFailed = true;
						}
					}
				}
				if (attachmentDownloadFailed)
				{
					msg.append("\nUnable to auto-download attachment(s)");
					if (DiscordMonitor.this.appconfig.attachmentDatastorePaths.isEmpty())
						msg.append(": no valid AttachmentDatastorePaths");
					msg.append(". See URL(s) for manual download.");
				}

				User author = message.getAuthor();                //The user that sent the message

				boolean bot = author.isBot();                    //This boolean is useful to determine if the User that
																// sent the Message is a BOT or not!

				if (event.isFromType(ChannelType.TEXT))         //If this message was sent to a Guild TextChannel
				{
					//Because we now know that this message was sent in a Guild, we can do guild specific things
					// Note, if you don't check the ChannelType before using these methods, they might return null due
					// the message possibly not being from a Guild!

					Guild guild = message.getGuild(); //The Guild that this message was sent in. (note, in the API, Guilds are Servers)
					TextChannel textChannel = message.getTextChannel(); //The TextChannel that this message was sent to.
					Member member = guild.getMember(author); //This Member that sent the message. Contains Guild specific information about the User!

					String name;
					if (message.isWebhookMessage())
					{
						name = author.getName();                //If this is a Webhook message, then there is no Member associated
					}                                           // with the User, thus we default to the author for name.
					else
					{
						name = member.getEffectiveName();       //This will either use the Member's nickname if they have one,
					}                                           // otherwise it will default to their username. (User#getName())

					System.out.printf("%d:(%s)[%s]<%s>: %s\n\n", message.getIdLong(), guild.getName(), textChannel.getName(), name, msg);
				}
				else if (event.isFromType(ChannelType.PRIVATE))
				{
					System.out.printf("%d:[DM]<%s>: %s\n\n", message.getIdLong(), author.getName(), msg);
				}
				else if (event.isFromType(ChannelType.GROUP))   //If this message was sent to a Group. This is CLIENT only!
				{
					//The message was sent in a Group. It should be noted that Groups are CLIENT only.
					Group group = message.getGroup();
					String groupName = group.getName() != null ? group.getName() : "";  //A group name can be null due to it being unnamed.

					System.out.printf("%d:[GRP: %s]<%s>: %s\n\n", message.getIdLong(), groupName, author.getName(), msg);
				}
			}
		}

		// Can apparently only happen in TextChannels (guilds).
		@Override
		public void onMessageBulkDelete(MessageBulkDeleteEvent event)
		{
			//These are provided with every event in JDA
			JDA jda = event.getJDA();                       //JDA, the core of the api.

			long serverId = event.getGuild().getIdLong();
			long channelId = event.getChannel().getIdLong();

			boolean declaredLoggableHit = false;
			for (DiscordMonitorTargetIdentifier targetid : DiscordMonitor.this.appconfig.logTargets)
			{
				if (!targetid.matches(serverId, channelId, null, null, null, MessageEventType.DELETE))
					continue;

				// All conditions passed.
				if (!declaredLoggableHit)
				{
					declaredLoggableHit = true;
					System.out.print("(i) Logging for:");
				}
				System.out.print(" " + targetid.identifierLabel);
			}
			if (declaredLoggableHit)
				System.out.println();

			boolean declaredNotificationHit = false;
			for (DiscordMonitorTargetIdentifier targetid : DiscordMonitor.this.appconfig.notificationWatchlist)
			{
				if (!targetid.matches(serverId, channelId, null, null, null, MessageEventType.DELETE))
					continue;

				// All conditions passed.
				if (!declaredNotificationHit)
				{
					declaredNotificationHit = true;
					System.out.print("/!\\ WATCHLIST HIT:");
				}
				System.out.print(" " + targetid.identifierLabel);
			}
			if (declaredNotificationHit)
				System.out.println();


			StringBuilder msg = new StringBuilder("\n")
				.append("Time: ").append(OffsetDateTime.now().format(DiscordMonitor.LOG_DATETIME_FORMAT)).append(" (approximate)")
				.append("\nMESSAGES DELETED:");
			for (String msgId : event.getMessageIds())
				msg.append(" ").append(msgId);

			//TODO Poll .sqlite db for previous message details

			TextChannel textChannel = (TextChannel)event.getChannel();
			System.out.printf("(%s)[%s]: %s\n\n", textChannel.getGuild().getName(), textChannel.getName(), msg);
		}
	}
}
