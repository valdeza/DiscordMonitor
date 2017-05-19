package com.github.valdeza.DiscordMonitor;

import net.dv8tion.jda.client.entities.Group;
import net.dv8tion.jda.core.entities.ChannelType;
import net.dv8tion.jda.core.entities.Message;
import net.dv8tion.jda.core.entities.MessageChannel;
import net.dv8tion.jda.core.entities.PrivateChannel;
import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.events.message.GenericMessageEvent;
import net.dv8tion.jda.core.events.message.MessageDeleteEvent;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;
import net.dv8tion.jda.core.events.message.MessageUpdateEvent;

class DiscordMonitorBotUtilities
{
	static boolean isTargetIdentifierMatchGeneric(DiscordMonitorTargetIdentifier tid, GenericMessageEvent event)
	{
		if (event.isFromType(ChannelType.VOICE))
		{
			System.out.println("warning: Encountered voice-type message. Assumed non-matching.");
			return false; //TODO Handle more gracefully?
		}
		
		// Get server ID (if needed)
		Long serverId = null;
		if (tid.serverId != null)
		{
			MessageChannel channel = event.getChannel();
			switch (event.getChannelType())
			{
				case GROUP:
					serverId = ((Group)channel).getIdLong();
					break;
				case PRIVATE:
					serverId = ((PrivateChannel)channel).getIdLong();
					break;
				case TEXT:
					serverId = ((TextChannel)channel).getGuild().getIdLong();
					break;
				case UNKNOWN:
					String channelName = event.getChannel().getName();
					if (channelName == null)
						channelName = "(unnamed group)";
					System.err.println("error: Encountered unknown channel type.\n"
						+ "Please update your DiscordMonitor client and contact the JDA development team if the problem persists.");
					System.err.printf("Problem occurred at: id=%d, name=\"%s\"", event.getChannel().getIdLong(), channelName);
					break;
				//case VOICE: break;
				default:
					System.err.printf("error: Unhandled channel type \"%s\"\n", event.getChannelType().name());
					break;
			}
		}
		
		// Try to extract userId and messageContent if a MessageReceivedEvent or MessageUpdatedEvent
		Message message = null;
		if (event instanceof MessageReceivedEvent)
			message = ((MessageReceivedEvent)event).getMessage();
		else if (event instanceof MessageUpdateEvent)
			message = ((MessageUpdateEvent)event).getMessage();
		
		Long channelId = null, userId = null;
		String messageContent = null;
		if (message != null)
		{
			channelId = message.getChannel().getIdLong();
			userId = message.getAuthor().getIdLong();
			messageContent = message.getStrippedContent();
		}
		//TODO Check against .sqlite DB to fetch user ID/message content for MessageDeleteEvents.
		
		MessageEventType eventType = null;
		if (event instanceof MessageReceivedEvent)
		{
			eventType = MessageEventType.NEW;
			if (message.isEdited())
				System.out.println("error: MessageReceivedEvent contains edited message.");
		}
		else if (event instanceof MessageUpdateEvent)
		{
			eventType = MessageEventType.EDIT;
			if (!message.isEdited())
				System.out.println("error: MessageUpdateEvent contains non-edited message.");
		}
		else if (event instanceof MessageDeleteEvent)
			eventType = MessageEventType.DELETE;
		else
			System.out.println("warning: Unsupported GenericMessageEvent provided. Ignoring event type for target identifier matching...");
			
		
		return tid.matches(serverId, channelId, userId, messageContent, eventType);
	}
}