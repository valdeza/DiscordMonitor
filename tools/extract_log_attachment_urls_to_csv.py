"""Gets attachment URLs from DiscordMonitor verbose logs.

A temporary solution for downloading attachments from certain groups, guilds, channels, and/or users.
(At the current time of writing, DiscordMonitor cannot be used to obtain snowflake IDs without cluttering the log file.)

This script outputs to .csv instead of directly downloading for review.
The .csv file can then be processed or filtered to be input to download_from_attachments_csv.py . Alternatively, the .csv file may be piped to wget or a download manager of your choice.

Working as of 20 Jun 2017, DiscordMonitor git-revision 457d6700f65c6c3ec58caaa38f0b466e61258725
"""

import argparse
import csv
import json
import re
import sys
import textwrap
import warnings

REGEX_STR = r"(?:(?P<msgid>\d+):\((?P<guildname>.+)\)\[(?P<channelname>.+)\]<(?P<username>.+)>: ?|(?P<dm_msgid>\d+):\[DM\]<(?P<dm_user1>.*) -> (?P<dm_user2>.*)>: ?|(?P<group_msgid>\d+):\[GRP: (?P<group_name>.*?)\]<(?P<group_username>.+)>: ?)\r?\n.*\r?\nMessage:(?:(?!(?:\d+):).*\r?\n)+Attachment\[0\]: (?P<attachmentjson>\{(?:.*\r?\n)*?\})"

MESSAGE_TYPE_GUILD = 'G'
MESSAGE_TYPE_DM    = 'D'
MESSAGE_TYPE_GROUP = 'g'

def main(args):
	warnings.simplefilter("always")

	regex = re.compile(REGEX_STR)

	if args.read_encoding is not None:
		args.logfile._encoding = args.read_encoding

	do_print_current_msgid = False
	is_debug_enabled = False
	if args.verbose is not None:
		if args.verbose >= 1:
			do_print_current_msgid = True
		if args.verbose >= 2:
			is_debug_enabled = True

	is_unspecified_target = args.group is None and args.guild is None and args.channel is None and args.user is None

	file_content = ""
	with args.logfile as fin:
		file_content = fin.read()

	with args.outcsv if args.outcsv.name == "<stdout>" else open(args.outcsv.name, mode='w', newline='') as fout:
		fieldnames = ["msgtype", "msgid", "group_name", "guild_name", "channel_name", "username", "dm_user1", "dm_user2", "attachment_id", "attachment_url", "attachment_proxy_url", "filename", "bytes_size"]
		csvwriter = csv.DictWriter(fout, fieldnames=fieldnames)
		csvwriter.writeheader()

		for match in regex.finditer(file_content):
			groupdict = match.groupdict()
			if is_debug_enabled:
				print(groupdict)

			is_dm = groupdict["dm_msgid"] is not None
			is_group = groupdict["group_msgid"] is not None
			is_guild = groupdict["msgid"] is not None
			msgtype = -1
			if is_dm:
				msgtype = MESSAGE_TYPE_DM
			elif is_group:
				msgtype = MESSAGE_TYPE_GROUP
			elif is_guild:
				msgtype = MESSAGE_TYPE_GUILD
			else:
				warnings.warn("Encountered unknown message type. Message skipped.", SyntaxWarning)

			assert is_dm ^ is_group ^ is_guild

			msgid = -1
			if is_dm:
				msgid = groupdict["dm_msgid"]
			elif is_group:
				msgid = groupdict["group_msgid"]
			elif is_guild:
				msgid = groupdict["msgid"]

			if do_print_current_msgid:
				print("Processing msgid:", msgid, "...", sep='', end='', flush=True)

			# Should process?
			## Check inclusion filtre
			if ((args.include_guild or args.include_dm or args.include_group) # is any included?
					and ((is_guild and not args.include_guild)
						or (is_dm and not args.include_dm)
						or (is_group and not args.include_group))):
				if is_debug_enabled:
					print(" reject: not included")
				continue

			## Check exclusion filtre
			if ((is_guild and args.exclude_guild)
					or (is_dm and args.exclude_dm)
					or (is_group and args.exclude_group)):
				if is_debug_enabled:
					print(" reject: excluded")
				continue

			## Check targets
			if (not (is_unspecified_target
					or (args.group is not None and groupdict["group_name"] in args.group)
					or (args.guild is not None and groupdict["guildname"] in args.guild)
					or (args.channel is not None and groupdict["channelname"] in args.channel)
					or (args.user is not None
						and (groupdict["username"] in args.user
						or groupdict["dm_user1"] in args.user
						or groupdict["dm_user2"] in args.user
						or groupdict["group_username"] in args.user)))):
				if is_debug_enabled:
					print(" reject: non-target")
				continue
			# Do process check passed

			attachmentjson = json.loads(groupdict["attachmentjson"])
			rowdata = {
				"msgtype" : msgtype,
				"msgid" : msgid,
				"group_name" : "(blank)" if groupdict["group_name"] == '' else groupdict["group_name"],
				"guild_name" : groupdict["guildname"],
				"channel_name" : groupdict["channelname"],
				"username" : groupdict["username"] if groupdict["group_username"] is None else groupdict["group_username"],
				"dm_user1" : groupdict["dm_user1"],
				"dm_user2" : groupdict["dm_user2"],
				"attachment_id" : attachmentjson["id"],
				"attachment_url" : attachmentjson["url"],
				"attachment_proxy_url" : attachmentjson["proxyUrl"],
				"filename" : attachmentjson["fileName"],
				"bytes_size" : attachmentjson["size"]
			}
			csvwriter.writerow(rowdata)
			if do_print_current_msgid:
				print(" written to file")
			if is_debug_enabled:
				print(rowdata)

if __name__ == "__main__":
	indentwrap1 = textwrap.TextWrapper(initial_indent='\t', subsequent_indent='\t')
	parser = argparse.ArgumentParser(
		formatter_class=argparse.RawDescriptionHelpFormatter, # permits newlines in description/epilog
		description="'group', guild', 'channel', and 'user' arguments can be supplied multiple times to specify additional targets.\n"
			+ "Not specifying any targets will output all attachment URLs.",
		epilog="example optional arguments:"
			+ "\n(none)\n"
				+ indentwrap1.fill("Basic operation. Will dump any and all found attachment metadata to .csv")
			+ "\n-g group1 -g group2 -G guild -u user in.log out.csv\n"
				+ indentwrap1.fill("Writes to .csv any attachment metadata meeting at least one of the specified targets.")
			+ "\n-g=''\n"
				+ indentwrap1.fill("Target (output all attachment metadata from) unnamed groups.")
			+ "\n--include-dm --include-group\n"
				+ indentwrap1.fill("Target only DM and group messages. Equivalent to \"--exclude-guild\".")
			+ "\n--exclude-guild -u user -v\n"
				+ indentwrap1.fill("Target DM and group messages from the specified user, outputting the current processed msgid along the way.")
			+ "\n-vv\n"
				+ indentwrap1.fill("Prints debug output: input regex match data and output csv data.\n"
				+ "Highly verbose: tee-ing or redirecting output to file is suggested."))
	arggroup_guild_handling = parser.add_mutually_exclusive_group()
	arggroup_guild_handling.add_argument("--include-guild",
		action="store_true")
	arggroup_guild_handling.add_argument("--exclude-guild",
		action="store_true")
	arggroup_dm_handling = parser.add_mutually_exclusive_group()
	arggroup_dm_handling.add_argument("--include-dm",
		action="store_true")
	arggroup_dm_handling.add_argument("--exclude-dm",
		action="store_true")
	arggroup_group_handling = parser.add_mutually_exclusive_group()
	arggroup_group_handling.add_argument("--include-group",
		action="store_true")
	arggroup_group_handling.add_argument("--exclude-group",
		action="store_true")
	parser.add_argument("--group", "-g",
		action="append",
		help="group name to target (note: group names can be blank. Target blank group names via \"-g=''\")")
	parser.add_argument("--guild", "-G",
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
	parsed_args = parser.parse_args()

	if parsed_args.exclude_guild and parsed_args.exclude_dm and parsed_args.exclude_group:
		sys.exit() # Nothing to do.

	if parsed_args.verbose is not None and parsed_args.verbose >= 2: # is debug enabled?
		print(parsed_args)
	main(parsed_args)
