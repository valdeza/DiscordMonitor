package com.github.valdeza.DiscordMonitor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.LinkedList;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.RandomStringUtils;

import com.github.valdeza.DiscordMonitor.DiscordMonitorTargetIdentifier.MessageProcessingOptions;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import net.dv8tion.jda.core.AccountType;
import net.dv8tion.jda.core.JDA;

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
	public LinkedList<File> attachmentDatastorePaths;
	public Boolean useTempDir;
	private static final boolean DEFAULT_VALUE_USE_TEMP_DIR = false;
	public Integer minFileSize;
	public Integer maxFileSize;
	public Long maxDatastoreSize;
	private long remainingDatastoreCapacity = 0;
	public File logDBLocation;
	public DiscordMonitorTargetIdentifier[] logTargets;
	public File notificationTextLogLocation;
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
			.registerTypeAdapter(File.class, new FileTypeDeserializer())
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

	private static class FileTypeDeserializer implements JsonDeserializer<File>
	{
		@Override
		public File deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException
		{
			return new File(json.getAsString());
		}
	}

	/** Initial validation checks prior to bot startup.
	 * The following checks are performed:
	 * <ul>
	 * <li> Mandatory fields are defined
	 * <li> Top-level elements are not null, assumed values and potentially undesirable program behaviour is stated
	 *      (latter is suppressable by explicitly defining blank values in supplied .profile.json configuration file)
	 * <li> Temporary directory added to field '{@link com.github.valdeza.DiscordMonitor.DiscordMonitorConfig#attachmentDatastorePaths AttachmentDatastorePaths}' via system property "java.io.tmpdir" (if requested by user)
	 * <li> Provided paths are valid:
	 * 	<ul>
	 * 	<li> {@link DiscordMonitorConfig#attachmentDatastorePaths AttachmentDatastorePaths} do not point to files
	 * 	<li> Neither {@link DiscordMonitorConfig#logDBLocation LogDBLocation} nor {@link DiscordMonitorConfig#notificationTextLogLocation NotificationTextLogLocation} point to a directory
	 * 	</ul>
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

		if (this.attachmentDatastorePaths != null && this.useTempDir == null)
		{
			this.useTempDir = DiscordMonitorConfig.DEFAULT_VALUE_USE_TEMP_DIR;
			System.out.println("warning: Field 'UseTempDir' undefined. Assuming 'false'.");
		}

		if (this.attachmentDatastorePaths == null)
			System.out.println("info: Field 'AttachmentDatastorePaths' is null. "
				+ "Attachment auto-downloading disabled.");
		else
		{
			if (this.useTempDir)
				this.attachmentDatastorePaths.add(new File(System.getProperty("java.io.tmpdir")));

			// This config option only accepts directory paths.
			for (File path : this.attachmentDatastorePaths)
				if (path.isFile())
					throw new IllegalArgumentException("error: AttachmentDatastorePath cannot be file: " + path.toString());
		}

		if (this.minFileSize <= 0)
			this.minFileSize = null;

		if (this.maxFileSize != null && this.maxFileSize < 0)
			throw new IllegalArgumentException("error: Field 'MaxFileSize' cannot be negative.");

		if (this.maxDatastoreSize != null && this.maxDatastoreSize < 0)
			throw new IllegalArgumentException("error: Field 'MaxDatastoreSize' cannot be negative.");

		if (this.logDBLocation == null)
			System.out.println("info: Field 'LogDBLocation' is null. "
				+ "Message activity logging disabled.");
		else if (this.logDBLocation.isDirectory())
			throw new IllegalArgumentException("error: LogDBLocation cannot be directory: " + this.logDBLocation.toString());

		if (this.logTargets != null)
			for (DiscordMonitorTargetIdentifier tid : this.logTargets)
				if (tid.messageProcessingOptions != null && tid.messageProcessingOptions.contains(MessageProcessingOptions.AUTODOWNLOAD_ATTACHMENTS))
					tid.messageProcessingOptions.add(MessageProcessingOptions.HAS_ATTACHMENTS);

		if (this.notificationTextLogLocation == null)
			System.out.println("info: Field 'NotificationTextLogLocation' is null. "
				+ "Watchlist notifications disabled.");
		else if (this.notificationTextLogLocation.isDirectory())
			throw new IllegalArgumentException("error: NotificationTextLogLocation cannot be directory: " + this.notificationTextLogLocation.toString());

		if (this.notificationWatchlist != null)
			for (DiscordMonitorTargetIdentifier tid : this.notificationWatchlist)
				if (tid.messageProcessingOptions != null && tid.messageProcessingOptions.contains(MessageProcessingOptions.AUTODOWNLOAD_ATTACHMENTS))
					tid.messageProcessingOptions.add(MessageProcessingOptions.HAS_ATTACHMENTS);
	}

	/** Validation checks to be performed on bot startup.
	 * The following checks are performed:
	 * <ul>
	 * <li> If client/user bot is configured to respond to other users, warns against this behaviour and deauthorises all users other than the one represented by the {@linkplain DiscordMonitorConfig#authToken bot's own token}.
	 *      <!-- Implemented as this is supposedly considered a bannable offense (according to the Discord API channel).
	 *           Overridable by setting {@link DiscordMonitorConfig#OVERRIDE_IGNORE_SELFBOT_REPLY_CHECK}. -->
	 * </ul>
	 * @param jda An <strong>already logged-in</strong> JDA instance this DMConfig is intended to be used with
	 */ // On selfbot usage: Supposedly considered a bannable offense (according to Discord API channel).
	public void validateJDA(JDA jda)
	{
		if (this.enableBotReply != null && this.enableBotReply)
		{
			if (DiscordMonitorConfig.OVERRIDE_IGNORE_SELFBOT_REPLY_CHECK)
				System.out.println("warning: This selfbot is configured to reply to other users, "
					+ "placing this account at risk of ban in public channels.");
			else if (this.authType != null && this.authType == AccountType.CLIENT)
			{ // Check and guard against selfbots replying to other users.
				boolean warningIssued = false;
				long selfUserId = jda.getSelfUser().getIdLong();
				for (DiscordMonitorTargetIdentifier tid : this.authorizedUsers)
				{
					if (tid.userId == null || !tid.userId.equals(selfUserId))
					{
						if (warningIssued)
						{
							System.out.println("warning: Detected selfbot possibly configured to reply to other users (prohibited action). "
								+ "All 'AuthorizedUsers' entries have been set to the user ID belonging to the provided 'AuthToken'.");
							warningIssued = true;
						}

						tid.userId = selfUserId;
					}
				}
			}
		}
	}

	/**
	 * <em>To be called prior to accessing {@linkplain com.github.valdeza.DiscordMonitor.DiscordMonitorConfig#attachmentDatastorePaths AttachmentDatastorePaths}.</em>
	 * <br>With announcement, updates the current head AttachmentDatastorePath if it fails to meet the {@linkplain com.github.valdeza.DiscordMonitor.DiscordMonitorConfig#maxDatastoreSize MaxDatastoreSize} restriction, if set.
	 */
	public void refreshCurrentAttachmentDatastorePath()
	{
		// Precondition: There is a specified limit to how large a datastore path can be.
		if (this.maxDatastoreSize == null)
			return;

		// Precondition: There exists at least one AttachmentDatastorePath to work with.
		if (this.attachmentDatastorePaths.isEmpty())
			return;

		// Precondition: Attachment auto-downloading not disabled.
		if (this.attachmentDatastorePaths == null)
			return;

		// Check if space remaining. If not, pop off path and notify.
		if (this.remainingDatastoreCapacity <= 0)
		{
			while (true) // Re-examine current datastore path until OK or no more paths
			{
				long currDirSize = FileUtils.sizeOfDirectory(this.attachmentDatastorePaths.peek());
				if (currDirSize < 0)
					System.out.println("error: Removed datastore path (size too large): " + this.attachmentDatastorePaths.remove());
				else if (currDirSize == 0)
					System.out.println("error: Removed datastore path (access denied): " + this.attachmentDatastorePaths.remove());
				else
				{ // Recheck directory size. Files may have been deleted since then.
					long remainingDatastoreCapacity = this.maxDatastoreSize - currDirSize;
					if (remainingDatastoreCapacity <= 0)
						System.out.println("warning: Removed datastore path (over size limit): " + this.attachmentDatastorePaths.remove());
					else
					{
						this.remainingDatastoreCapacity = remainingDatastoreCapacity;
						return; // Current datastore path is OK
					}
				}

				if (this.attachmentDatastorePaths.isEmpty())
				{
					System.out.println("warning: No datastore paths remaining. Can no longer auto-download attachments.");
					return;
				}
			}
		}
	}

	/** <em>It is expected to have {@linkplain com.github.valdeza.DiscordMonitor.DiscordMonitorConfig#refreshCurrentAttachmentDatastorePath() refreshed} the current {@linkplain com.github.valdeza.DiscordMonitor.DiscordMonitorConfig#attachmentDatastorePaths AttachmentDatastorePath} prior to calling this method.</em>
	 * <br>Using the current AttachmentDatastorePath and given <em>filename</em>, generates an absolute file path to a file location to be written to. Uniqueness is guaranteed by appending random alphanumeric characters to the end of the given <em>filename</em>. If the current AttachmentDatastorePath refers to a directory that does not exist yet, it will be created (along with parent directories, if needed).
	 * @param filename Name of the file to save to--including extension
	 * @return An absolute file path to be supplied to the aforementioned {@link net.dv8tion.jda.core.entities.Message.Attachment#download(File) download} method
	 * @throws UnsupportedOperationException Thrown if this config's {@linkplain DiscordMonitorConfig#attachmentDatastorePaths AttachmentDatastorePaths} is in an invalid state (is null or empty)
	 * @see net.dv8tion.jda.core.entities.Message.Attachment#download(File)
	 * @see net.dv8tion.jda.core.entities.Message.Attachment#getFileName()
	 */
	public File generateDownloadFilepath(String filename) throws UnsupportedOperationException
	{
		if (this.attachmentDatastorePaths == null)
			throw new UnsupportedOperationException("Cannot generate download filepath: attachment auto-download disabled");
		if (this.attachmentDatastorePaths.isEmpty())
			throw new UnsupportedOperationException("Cannot generate download filepath: no AttachmentDatastorePaths remaining.");
		if (!this.attachmentDatastorePaths.peek().exists())
			this.attachmentDatastorePaths.peek().mkdirs();

		String dirpath = this.attachmentDatastorePaths.peek().toString();
		if (!dirpath.endsWith(File.separator))
			dirpath += File.separatorChar;

		String basename = FilenameUtils.removeExtension(filename);
		if (!basename.equals(""))
			basename += '_'; // Separates original file name from random tag.

		String extension = FilenameUtils.getExtension(filename);
		if (!extension.equals(""))
			extension = '.' + extension;

		// Try generating filepaths with random suffixes until a unique filepath is found.
		String randsuffix = "";
		while (true)
		{
			randsuffix += RandomStringUtils.randomAlphanumeric(1);
			File fileCandidate = new File(dirpath + basename + randsuffix + extension);
			if (!fileCandidate.exists())
				return fileCandidate;
		}
	}

	public void notifySpentAttachmentDatastoreCapacity(long numBytes)
	{
		this.remainingDatastoreCapacity -= numBytes;
	}

	/**
	 * Removes the current {@linkplain com.github.valdeza.DiscordMonitor.DiscordMonitorConfig#attachmentDatastorePaths AttachmentDatastorePath},
	 * returning the subsequent AttachmentDatastorePath or, if the list of AttachmentDatastorePaths is empty, null.
	 * @param isExpected Specify 'true' to tag the deletion message as 'info'; otherwise 'false' tags the deletion message as 'warning'.
	 * @return Next AttachmentDatastorePath
	 * @throws java.util.NoSuchElementException Thrown if the list of AttachmentDatastorePaths is empty.
	 */
	public File nextAttachmentDatastorePath(boolean isExpected)
	{
		this.attachmentDatastorePaths.remove();
		System.out.println((isExpected ? "info" : "warning") + ": Removed datastore path (over size limit): " + this.attachmentDatastorePaths.remove());
		return this.attachmentDatastorePaths.peek();
	}
}
