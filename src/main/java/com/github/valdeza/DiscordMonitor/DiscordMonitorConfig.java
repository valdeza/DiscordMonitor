package com.github.valdeza.DiscordMonitor;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.LinkedList;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.github.valdeza.DiscordMonitor.DiscordMonitorTargetIdentifier.MessageProcessingOptions;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import net.dv8tion.jda.core.AccountType;

class DiscordMonitorConfig
{
	public AccountType authType;
	public String authToken;
	public Boolean enableBotReply;
	private static final boolean DEFAULT_VALUE_ENABLE_BOT_REPLY = false;
	public String commandPrefix;
	public DiscordMonitorTargetIdentifier[] authorizedUsers;
	/** Set to 'true' to allow selfbots to reply to other users. */
	private static final boolean OVERRIDE_IGNORE_SELFBOT_REPLY_CHECK = false;
	public LinkedList<String> attachmentDatastorePaths;
	public String logDBLocation;
	public DiscordMonitorTargetIdentifier[] logTargets;
	public String notificationTextLogLocation;
	public DiscordMonitorTargetIdentifier[] notificationWatchlist;

	/**
	 * @param filepath File path pointing to a .profile.json file.
	 * @return A {@link com.github.valdeza.DiscordMonitor.DiscordMonitorConfig DiscordMonitorConfig} deserialised from the provided <em>filepath</em>.
	 * @throws FileNotFoundException Thrown if provided file path does not exist.
	 */
	public static DiscordMonitorConfig loadFromFile(String filepath) throws FileNotFoundException
	{
		Gson gson = new GsonBuilder()
			.serializeNulls()
			.setFieldNamingPolicy(FieldNamingPolicy.UPPER_CAMEL_CASE)
			.disableHtmlEscaping()
			.registerTypeAdapter(Pattern.class, new PatternTypeDeserializer())
			.create();
		DiscordMonitorConfig config = gson.fromJson(new FileReader(filepath), DiscordMonitorConfig.class);
		config.validateInit();
		return config;
	}

	private static class PatternTypeDeserializer implements JsonDeserializer<Pattern>
	{
		@Override
		public Pattern deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException
		{
			String strPattern = json.getAsString();
			try
			{
				return Pattern.compile(strPattern);
			}
			catch (PatternSyntaxException e)
			{
				throw new JsonParseException(e);
			}
		}
	}

	/** Initial validation checks prior to bot startup.
	 * The following checks are performed:
	 * <ul>
	 * <li> Mandatory fields are defined
	 * <li> Top-level elements are not null, assumed values and potentially undesirable program behaviour is stated
	 *      (latter is suppressable by explicitly defining blank values in supplied .profile.json configuration file)
	 * <li> Temporary directory added to field 'AttachmentDatastorePaths' via system property "java.io.tmpdir"
	 * <li> '{@linkplain com.github.valdeza.DiscordMonitor.DiscordMonitorTargetIdentifier#messageProcessingOptions MessageProcessingOptions}' of '{@linkplain DiscordMonitorTargetIdentifier DMTargetIdentifiers}' are set correctly
	 *      (where "{@link DiscordMonitorTargetIdentifier.MessageProcessingOptions#AUTODOWNLOAD_ATTACHMENTS AUTODOWNLOAD_ATTACHMENTS}" are always accompanied by "{@link DiscordMonitorTargetIdentifier.MessageProcessingOptions#HAS_ATTACHMENTS HAS_ATTACHMENTS}")
	 * </ul>
	 * Note: Does <em>not</em> check for things like correct regular expression syntax or whether the supplied user/bot token is genuine.
	 *
	 * @throws IllegalArgumentException Thrown if the program is unable to continue with this configuration.
	 */
	public void validateInit() throws IllegalArgumentException
	{
		if (this.authType == null)
			throw new IllegalArgumentException("error: Field 'AuthType' invalid or undefined.");

		if (this.authToken == null || this.authToken.equals(""))
			throw new IllegalArgumentException("error: Field 'AuthToken' undefined.");

		if (this.enableBotReply == null)
		{
			this.enableBotReply = DiscordMonitorConfig.DEFAULT_VALUE_ENABLE_BOT_REPLY;
			System.out.println("warning: Field 'EnableBotReply' undefined. Assuming 'false'.");
		}

		if (this.commandPrefix == null)
			System.out.println("info: Field 'CommandPrefix' is null. "
				+ "This bot will never respond to commands.");
		else if (this.commandPrefix.equals(""))
			System.out.println("info: Field 'CommandPrefix' undefined. "
				+ "All received messages will be interpreted as commands.");

		if (this.authorizedUsers == null)
		{
			this.authorizedUsers = new DiscordMonitorTargetIdentifier[]{};
			System.out.println("warning: Field 'AuthorizedUsers' undefined. "
				+ "This bot will be non-interactive and will never respond to commands. "
				+ "Specify \"AuthorizedUsers\":[] in supplied .profile.json to suppress this warning.");
		}

		if (this.attachmentDatastorePaths == null)
			this.attachmentDatastorePaths = new LinkedList<String>();
		// Using temporary directory as fallback
		this.attachmentDatastorePaths.add(System.getProperty("java.io.tmpdir"));

		for (DiscordMonitorTargetIdentifier tid : this.logTargets)
			if (tid.messageProcessingOptions != null && tid.messageProcessingOptions.contains(MessageProcessingOptions.AUTODOWNLOAD_ATTACHMENTS))
				tid.messageProcessingOptions.add(MessageProcessingOptions.HAS_ATTACHMENTS);

		for (DiscordMonitorTargetIdentifier tid : this.notificationWatchlist)
			if (tid.messageProcessingOptions != null && tid.messageProcessingOptions.contains(MessageProcessingOptions.AUTODOWNLOAD_ATTACHMENTS))
				tid.messageProcessingOptions.add(MessageProcessingOptions.HAS_ATTACHMENTS);
	}
}
