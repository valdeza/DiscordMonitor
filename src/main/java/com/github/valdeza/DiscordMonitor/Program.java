package com.github.valdeza.DiscordMonitor;

import java.io.FileNotFoundException;
import java.util.Scanner;

public class Program
{
	public static void main(String[] args)
	{
		String profilepath;
		if (args.length > 1)
		{
			System.out.println("error: expected usage: DiscordMonitor.jar <path\\to\\.profile.json>");
			return;
		} else if (args.length == 1)
		{
			profilepath = args[0];
		} else
		{
			System.out.print(".profile.json path? ");
			profilepath = new Scanner(System.in).nextLine();
		}

		DiscordMonitor app;
		try
		{
			app = new DiscordMonitor(DiscordMonitorConfig.loadFromFile(profilepath));
		} catch (FileNotFoundException e)
		{
			System.out.println("error: specified .profile.json does not exist");
			return;
		}
		app.start();
	}
}
