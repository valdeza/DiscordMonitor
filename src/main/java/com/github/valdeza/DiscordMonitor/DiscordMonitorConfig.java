package com.github.valdeza.DiscordMonitor;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.util.LinkedList;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
	public String commandPrefix;
	public DiscordMonitorTargetIdentifier[] authorizedUsers;
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
		return gson.fromJson(new FileReader(filepath), DiscordMonitorConfig.class);
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
}
