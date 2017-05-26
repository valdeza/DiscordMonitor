package com.github.valdeza.DiscordMonitor;

import java.util.EnumSet;
import java.util.regex.Pattern;

class DiscordMonitorTargetIdentifier
{
	private static final Pattern PATTERN_WHITESPACE = Pattern.compile("\\p{Space}");
	
	enum MessageProcessingOptions
	{
		/** Strip the message of all whitespace before attempting regex matching. Intended to countract possible watchlist avoidance. */
		IGNORE_WHITESPACE,
		
		//TODO Implement below items
		/** Require that the message has attachments. */
		HAS_ATTACHMENTS,
		
		/** Download attachments, if any. Implies {@link MessageProcessingOptions#HAS_ATTACHMENTS}. */
		AUTODOWNLOAD_ATTACHMENTS
	}

	public String identifierLabel;
	public Long serverId;
	public Long channelId;
	public Long userId;
	/**
	 * Supports embedded flags. For more information, see Java documentation for Pattern.
	 * @see java.util.regex.Pattern
	 */
	public Pattern messageRegex;
	public EnumSet<MessageProcessingOptions> messageProcessingOptions;
	public EnumSet<MessageEventType> eventType;

	/** Returns whether this DMTargetIdentifier matches the given parametres.
	 * null may be provided for any of the parametres to skip match checks for that parametre
	 * (e.g. specifying all nulls will return true).
	 *
	 * @param serverId
	 * @param channelId Note: Can be the same as 'serverId' if the ID given corresponds to the general/public/default channel of a guild/server.
	 * @param userId
	 * @param messageContent Message content expected to be {@linkplain net.dv8tion.jda.core.entities.Message#getStrippedContent() stripped of Markdown formatting characters}.
	 * @param hasMessageAttachment
	 * @param eventType
	 */
	public boolean matches(Long serverId, Long channelId, Long userId, String messageContent, Boolean hasMessageAttachment, MessageEventType eventType)
	{
		if (this.serverId != null && serverId != null && !this.serverId.equals(serverId))
			return false;

		if (this.channelId != null && channelId != null && !this.channelId.equals(channelId))
			return false;

		if (this.userId != null && userId != null && !this.userId.equals(userId))
			return false;
		
		if (this.messageRegex != null && messageContent != null)
		{
			if (this.messageProcessingOptions != null && !this.messageProcessingOptions.isEmpty())
			{ // Prepare custom-processed string.
				String messageProc = messageContent;
				
				if (this.messageProcessingOptions.contains(MessageProcessingOptions.IGNORE_WHITESPACE))
					messageProc = PATTERN_WHITESPACE.matcher(messageProc).replaceAll("");
				
				// String processed. Attempt regex match.
				if (!this.messageRegex.matcher(messageContent).find())
					return false;
			}
			else if (!this.messageRegex.matcher(messageContent).find())
				return false;
		}

		if (this.messageProcessingOptions != null && this.messageProcessingOptions.contains(MessageProcessingOptions.HAS_ATTACHMENTS)
				&& hasMessageAttachment != null && !hasMessageAttachment)
			return false;

		if (this.eventType != null && eventType != null && !this.eventType.contains(eventType))
			return false;

		return true;
	}
}
