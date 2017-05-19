package com.github.valdeza.DiscordMonitor;

import java.time.format.DateTimeFormatter;
import java.util.List;

import javax.security.auth.login.LoginException;

import net.dv8tion.jda.client.entities.Group;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.JDABuilder;
import net.dv8tion.jda.core.entities.ChannelType;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.Member;
import net.dv8tion.jda.core.entities.Message;
import net.dv8tion.jda.core.entities.Message.Attachment;
import net.dv8tion.jda.core.entities.MessageEmbed;
import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.entities.User;
import net.dv8tion.jda.core.events.message.GenericMessageEvent;
import net.dv8tion.jda.core.events.message.MessageBulkDeleteEvent;
import net.dv8tion.jda.core.events.message.MessageDeleteEvent;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;
import net.dv8tion.jda.core.events.message.MessageUpdateEvent;
import net.dv8tion.jda.core.exceptions.RateLimitedException;
import net.dv8tion.jda.core.hooks.ListenerAdapter;

class DiscordMonitor
{
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
				.addEventListener(new DiscordMonitorListenerAdapter())
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
				eventHandlerMessageReceivedOrUpdate(event);
		}
		
		@Override
		public void onMessageUpdate(MessageUpdateEvent event)
		{
			eventHandlerMessageReceivedOrUpdate(event);
		}
		
		private void eventHandlerMessageReceivedOrUpdate(GenericMessageEvent event)
		{
			//These are provided with every event in JDA
			JDA jda = event.getJDA();                       //JDA, the core of the api.

			//Event specific information
			Message message; //The message that was received.
			if (event instanceof MessageReceivedEvent)
				message = ((MessageReceivedEvent)event).getMessage();
			else if (event instanceof MessageUpdateEvent)
				message = ((MessageUpdateEvent)event).getMessage();
			else
				throw new IllegalArgumentException("Provided event is not of type MessageReceivedEvent nor MessageUpdateEvent");
			
			User author = message.getAuthor();                //The user that sent the message

			boolean bot = author.isBot();                    //This boolean is useful to determine if the User that
															// sent the Message is a BOT or not!
			
			boolean isGuildEvent = event.getChannelType().isGuild();
			Guild guild_ = message.getGuild(); //The Guild that this message was sent in. (note, in the API, Guilds are Servers)
			TextChannel guild_textChannel = message.getTextChannel(); //The TextChannel that this message was sent to.
			Member guild_member = null; //This Member that sent the message. Contains Guild specific information about the User!
			if (isGuildEvent)
				guild_member = guild_.getMember(author);
			
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
			}
			if (declaredNotificationHit)
				System.out.println();
			
			final DateTimeFormatter dateTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSSXXXXX");
			StringBuilder msg = new StringBuilder("\n")
				.append("Time: ").append(
					message.isEdited()
					? message.getEditedTime().format(dateTimeFormat)
					: message.getCreationTime().format(dateTimeFormat))
				.append("\nMessage: ").append(message.getContent()); //This returns a human readable version of the Message. Similar to what you would see in the client.
			List<MessageEmbed> embeds = message.getEmbeds();
			int currEmbedCount = 0;
			for (MessageEmbed embedProbe : embeds)
			{
				msg.append(String.format("\nEmbed[%d]: { ", currEmbedCount++));
				//TODO Use Gson?
				msg.append("}");
			}
			
			List<Attachment> attachments = message.getAttachments();
			for (Attachment attachmentProbe : attachments)
			{
				msg.append(String.format("\nAttachment[%d]: { ", currEmbedCount++));
				//TODO Use Gson?
				msg.append("}");
			}

			if (event.isFromType(ChannelType.TEXT))         //If this message was sent to a Guild TextChannel
			{
				//Because we now know that this message was sent in a Guild, we can do guild specific things
				// Note, if you don't check the ChannelType before using these methods, they might return null due
				// the message possibly not being from a Guild!

				String name;
				if (message.isWebhookMessage())
				{
					name = author.getName();                //If this is a Webhook message, then there is no Member associated
				}                                           // with the User, thus we default to the author for name.
				else
				{
					name = guild_member.getEffectiveName();       //This will either use the Member's nickname if they have one,
				}                                           // otherwise it will default to their username. (User#getName())

				System.out.printf("%d:(%s)[%s]<%s>: %s\n\n", message.getIdLong(), guild_.getName(), guild_textChannel.getName(), name, msg);
			}
			else if (event.isFromType(ChannelType.GROUP))   //If this message was sent to a Group. This is CLIENT only!
			{
				//The message was sent in a Group. It should be noted that Groups are CLIENT only.
				Group group = message.getGroup();
				String groupName = group.getName() != null ? group.getName() : "";  //A group name can be null due to it being unnamed.

				System.out.printf("%d:[GRP: %s]<%s>: %s\n\n", message.getIdLong(), groupName, author.getName(), msg);
			}
		}
		
		@Override
		public void onMessageDelete(MessageDeleteEvent event)
		{
			//TODO
		}
		
		@Override
		public void onMessageBulkDelete(MessageBulkDeleteEvent event)
		{
			//TODO
		}
	}
}
