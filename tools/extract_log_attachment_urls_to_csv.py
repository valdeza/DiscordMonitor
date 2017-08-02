"""Gets attachment URLs from DiscordMonitor verbose logs.

A temporary solution for downloading attachments from certain guilds, channels, and/or users.
(At the current time of writing, DiscordMonitor cannot be used to obtain snowflake IDs without cluttering the log file.)

This script outputs to .csv instead of directly downloading for review.
The .csv file can then be processed and piped to wget or a download manager of your choice.

Working as of 20 Jun 2017, DiscordMonitor git-revision 457d6700f65c6c3ec58caaa38f0b466e61258725
"""

import argparse
import csv
import json
import re

def main(args):
	REGEX_STR = r"(?:(?P<msgid>\d+):\((?P<guildname>.+)\)\[(?P<channelname>.+)\]<(?P<username>.+)>: |(?P<dm_msgid>\d+):\[DM\]<(?P<dm_user1>.*) -> (?P<dm_user2>.*)>:\s?)\r?\n.*\r?\nMessage:(?:(?!(?:\d+):).*\r?\n)+Attachment\[0\]: (?P<attachmentjson>\{(?:.*\r?\n)*?\})"
	regex = re.compile(REGEX_STR)

	if args.read_encoding is not None:
		args.logfile._encoding = args.read_encoding

	do_print_current_msgid = False
	if args.verbose is not None and args.verbose >= 1:
		do_print_current_msgid = True

	is_unspecified_target = args.guild is None and args.channel is None and args.user is None

	file_content = ""
	with args.logfile as fin:
		file_content = fin.read()
	# Workaround: Some systems (Windows) will print an extra newline between each record
	with args.outcsv if args.outcsv.name == "<stdout>" else open(args.outcsv.name, mode='w', newline='') as fout:
		fieldnames = ["msgid", "guild_name", "channel_name", "username", "dm_msgid", "dm_user1", "dm_user2", "attachment_id", "attachment_url", "attachment_proxy_url", "filename", "bytes_size"]
		csvwriter = csv.DictWriter(fout, fieldnames=fieldnames)
		csvwriter.writeheader()

		for match in regex.finditer(file_content):
			groupdict = match.groupdict()
			is_dm = groupdict["dm_msgid"] is not None
			if do_print_current_msgid:
				print("Processing msgid:", (groupdict["msgid"] if not is_dm else groupdict["dm_msgid"]), "... ", sep='', flush=True)

			# Check whether to process this message according to DM-handling args and var is_dm.
			# Reference logic table:
			# dm_only ignore_dm is_dm | is_dm_check_ok
			# 0       0         *     | 1
			# 0       1         0     | 1
			# 0       1         1     | 0
			# 1       0         0     | 0
			# 1       0         1     | 1
			# 1       1         *     | X # disregard output, dm_only and ignore_dm are mutually exclusive
			is_dm_check_ok = args.ignore_dm ^ is_dm or not args.dm_only ^ args.ignore_dm
			is_target_check_ok = (is_unspecified_target
				or (args.guild is not None and groupdict["guildname"] in args.guild)
				or (args.channel is not None and groupdict["channelname"] in args.channel)
				or (args.user is not None
					and (groupdict["username"] in args.user
					or groupdict["dm_user1"] in args.user
					or groupdict["dm_user2"] in args.user)))
			if is_dm_check_ok and is_target_check_ok:
				attachmentjson = json.loads(groupdict["attachmentjson"])
				csvwriter.writerow({
					"msgid" : groupdict["msgid"],
					"guild_name" : groupdict["guildname"],
					"channel_name" : groupdict["channelname"],
					"username" : groupdict["username"],
					"dm_msgid" : groupdict["dm_msgid"],
					"dm_user1" : groupdict["dm_user1"],
					"dm_user2" : groupdict["dm_user2"],
					"attachment_id" : attachmentjson["id"],
					"attachment_url" : attachmentjson["url"],
					"attachment_proxy_url" : attachmentjson["proxyUrl"],
					"filename" : attachmentjson["fileName"],
					"bytes_size" : attachmentjson["size"]
				})

if __name__ == "__main__":
	parser = argparse.ArgumentParser(
		description="'guild', 'channel', and 'user' arguments can be supplied multiple times to specify additional targets. Not specifying any targets will output all attachment URLs.")
	arggroup_dm_handling = parser.add_mutually_exclusive_group()
	arggroup_dm_handling.add_argument("--dm-only",
		action="store_true",
		help="specify to only target DMs")
	arggroup_dm_handling.add_argument("--ignore-dm",
		action="store_true",
		help="specify to exclude DMs")
	parser.add_argument("--guild", "-g",
		action="append",
		help="guild name to target")
	parser.add_argument("--channel", "-c",
		action="append",
		help="channel name to target")
	parser.add_argument("--user", "-u",
		action="append",
		help="user name to target")
	parser.add_argument("logfile",
		type=argparse.FileType('r', errors="surrogateescape"),
		help="DiscordMonitor-generated log file to parse")
	parser.add_argument("--read-encoding",
		help="input file encoding",
		metavar="ENCODING")
	parser.add_argument("outcsv",
		type=argparse.FileType('w'),
		help=".csv output file path")
	parser.add_argument("--verbose", "-v",
		action="count",
		help="verbosity level, increase by specifying multiple times: 0-silent, 1-display current processed msgid, 2-debug info")
	main(parser.parse_args())
