package bot;

import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.entities.*;
import net.dv8tion.jda.core.events.ReadyEvent;
import net.dv8tion.jda.core.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;
import net.dv8tion.jda.core.hooks.ListenerAdapter;
import net.dv8tion.jda.core.managers.GuildController;

import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.*;
import java.util.*;

public class BotCore extends ListenerAdapter {
    private String prefix = "!";
    private String backupFile = "backup.txt";
    private String MOD_ID;

    private LinkedHashMap<String, Command> commands;
    private LinkedHashMap<String, PUG> pugs;
    private LinkedHashMap<User, ZoneId> timeZones;

    BotCore(String announcementID, String NO_DM_ID, String MOD_ID) {
        this.MOD_ID = MOD_ID;

        this.commands = new LinkedHashMap<>();

        this.pugs = new LinkedHashMap<>();
        this.timeZones = new LinkedHashMap<>();

        //START COMMAND DEFINITIONS:
        commands.put("create", new Command (true, true,
                "!create [PUG name], [time], [optional: description]",
                "Creates a pug at the given time. Time should be in the format HH:MM [am/pm] [Time Zone (optional " +
                        "if you already registered a time zone)] [optional: MM-DD-YYYY]. Date defaults to today if none " +
                        "present. Year may be left off, defaults to this year. Register your time zone with !timezone.") {
            @Override
            String processServerMessage(Scanner args, MessageReceivedEvent message) throws Exception {
                String pugName = args.next();

                if (pugs.containsKey(pugName)) {
                    throw new IllegalCommandArgumentException("You cannot create a pug with the same name as an " +
                            "existing one.");
                }

                ZonedDateTime pugTime = parseTime(args.next(), message.getAuthor());

                String pugDescription = "";
                if (args.hasNext()) {
                    args.skip("\\s*,\\s*");
                    pugDescription = args.nextLine();
                }

                Guild guild = message.getGuild();

                pugs.put(pugName, new PUG(pugTime, pugDescription, message.getAuthor(), guild, pugName, 5,
                        announcementID, NO_DM_ID));

                return "PUG created successfully.";
            }
        });

        commands.put("cancel", new Command(true, true,
                "!cancel [PUG name]",
                "Deletes the named PUG and informs its players and watchers of the cancellation. This action is " +
                        "irreversible and currently does not ask \"Are you sure?\" or anything, so be careful.") {
            @Override
            String processServerMessage (Scanner args, MessageReceivedEvent message) throws Exception {
                pugs.remove(getPugName(args)).cancel();
                return "PUG successfully canceled.";
            }
        });

        commands.put("reschedule", new Command(true, true,
                "!reschedule [PUG name], [new time]",
                "Changes named pug to occur at given time. Informs all players and watchers of the change." +
                        "Time should be in the format HH:MM [am/pm] [Time Zone (optional if you already registered a " +
                        "time zone)] [optional: MM-DD-YYYY]. Date defaults to today if none present. Year may be left " +
                        "off, defaults to this year. Register your time zone with !timezone.") {
            @Override
            String processServerMessage(Scanner args, MessageReceivedEvent message) throws Exception {
                String pugName = getPugName(args);
                pugs.get(pugName).reschedule(parseTime(args.next(), message.getAuthor()));
                return "PUG rescheduled successfully";
            }
        });

        commands.put("transfer", new Command(true, true,
                "!transfer [PUG name], [optional: @user]",
                "Makes the named user the new mod of named PUG, or the command author if no user is named. " +
                        "The user must themselves be a mod.") {
            @Override
            String processServerMessage(Scanner args, MessageReceivedEvent message) throws Exception {
                PUG pug = pugs.get(getPugName(args));
                if (args.hasNext()) {
                    Member newMod = message.getMessage().getMentionedMembers().get(0);
                    if (this.authenticate(newMod, MOD_ID)) {
                        pug.changeMod(newMod.getUser());
                        return "PUG successfully transferred to " + newMod.getEffectiveName() + ".";
                    } else {
                        throw new IllegalCommandArgumentException("New mod must be a moderator.");
                    }
                } else {
                    pug.changeMod(message.getAuthor());
                    return "PUG successfully transferred to " + message.getMember().getEffectiveName() + ".";
                }
            }
        });

        commands.put("close", new Command(true, true,
                "!close [PUG name]",
                "Closes and deletes the named PUG. Currently just acts like !cancel except it doesn't notify anyone, " +
                        "but more functionality will be added in the future, hopefully.") {
            @Override
            String processServerMessage(Scanner args, MessageReceivedEvent message) throws Exception {
                pugs.remove(getPugName(args)).close();
                return "PUG successfully closed. Thank you for using PUGchamp!";
            }
        });

        commands.put("watch", new UserInfoCommand(false, true,
                "!watch [PUG name]",
                "Registers you as a watcher of the named pug. You will get updates about the PUG, but will not " +
                        "count toward the required 12 players. Use this if you are interested in playing but aren't " +
                        "sure if you can make it, or could play if the time changes slightly, etc. If you are " +
                        "currently a player in the pug, you will no longer be one.") {
            @Override
            String processUser(Scanner args, User user) throws Exception {
                String pugName = getPugName(args);
                pugs.get(pugName).registerWatcher(user);
                return "You are now watching " + pugName;
            }
        });

        commands.put("join", new UserInfoCommand(false, true,
                "!join [PUG name]",
                "Registers you as a player in the named PUG. If you are currently a watcher, you will no longer " +
                        "be one.") {
            @Override
            String processUser(Scanner args, User user) throws Exception {
                String pugName = getPugName(args);
                pugs.get(pugName).registerPlayer(user);
                return "You are now playing in " + pugName;
            }
        });

        commands.put("add", new Command(true, true,
                "!add @user, [PUG name]",
                "Adds the named user as a player in the named PUG, as though they had typed !join [PUG name].") {
            @Override
            String processServerMessage(Scanner args, MessageReceivedEvent message) throws Exception {
                Member player = message.getMessage().getMentionedMembers().get(0);
                args.next();
                pugs.get(getPugName(args)).registerPlayer(player.getUser());
                return "Player added successfully.";
            }
        });

        commands.put("leave", new UserInfoCommand(false, true,
                "!leave [PUG name]",
                "Removes you from the named PUG. You will no longer get updates, and will no longer count toward " +
                        "the required 12 players if you were a player.") {
            @Override
            String processUser(Scanner args, User user) throws Exception {
                pugs.get(getPugName(args)).removePlayer(user);
                return "Left PUG successfully.";
            }
        });

        commands.put("remove", new Command(true, true,
                "!remove @user, [PUG name]",
                "Removes named user from the named PUG, as though they had typed !leave [PUG name].") {
            @Override
            String processServerMessage(Scanner args, MessageReceivedEvent message) throws Exception {
                Member player = message.getMessage().getMentionedMembers().get(0);
                args.next();
                pugs.get(getPugName(args)).removePlayer(player.getUser());
                return "Player removed successfully.";
            }
        });

        commands.put("list", new Command(false, false,
                "!list [time zone (optional if you already registered a time zone)]",
                "Lists all active pugs, with times converted to given time zone. " +
                        "For more info about a specific PUG, use !info.") {
            @Override
            String processServerMessage(Scanner args, MessageReceivedEvent message) throws Exception {
                String list = "Here are the currently active PUGs:\n";
                ZoneId zone = getZone(message.getAuthor(), args);

                for (PUG pug : pugs.values()) {
                    list += pug.briefInfo(zone) + "\n";
                }

                return list;
            }
        });

        commands.put("info", new UserInfoCommand(false, false,
                "!info [PUG name], [time zone (optional if you already registered a time zone)]",
                "returns info about named pug, with times in given time zone - " +
                        "more detailed than the data from !list") {
            @Override
            String processUser(Scanner args, User user) throws Exception {
                String pug = getPugName(args);
                return pugs.get(pug).fullInfo(getZone(user, args));
            }
        });

        commands.put("players", new UserInfoCommand(false, false,
                "!players [PUG name]",
                "Returns a list of people playing in named PUG.") {
            @Override
            String processUser(Scanner args, User u) throws Exception {
                return pugs.get(getPugName(args)).playerList();
            }
        });

        commands.put("watchers", new UserInfoCommand(false, false,
                "!watchers [PUG name]",
                "Returns a list of people watching the named PUG.") {
            @Override
            String processUser(Scanner args, User u) throws Exception {
                return pugs.get(getPugName(args)).watcherList();
            }
        });

        commands.put("dms", new Command(false, false,
                "!DMs [on/off]",
                "Adds or removes the @Don't DM me role to you.") {
            @Override
            String processServerMessage(Scanner args, MessageReceivedEvent message) throws Exception {
                String state = args.next().toLowerCase();

                Role noDMs = message.getGuild().getRoleById(NO_DM_ID);
                GuildController controller = message.getGuild().getController();

                if (state.equals("off")) {
                    controller.addSingleRoleToMember(message.getMember(), noDMs).queue();
                }
                else if (state.equals("on")) {
                    controller.removeSingleRoleFromMember(message.getMember(), noDMs).queue();
                }
                else {
                    throw new IllegalArgumentException();
                }

                return "DMs turned " + state + ".";
            }
        });

        commands.put("timezone", new Command(false, true,
                "!timezone [time zone]",
                "registers your time zone as the given time zone. Any commands that involve time will use the " +
                        "registered time zone if none is specified. !timezone on its own will display your currently " +
                        "registered time zone, if you have one.") {
            @Override
            String processServerMessage(Scanner args, MessageReceivedEvent message) throws Exception {
                if (args.hasNext()) {
                    timeZones.put(message.getAuthor(), parseZone(args.next()));
                    return "Time zone registered successfully! Make sure to use -DT instead of -ST during daylight " +
                            "savings if you're in a country/state that uses it.";
                } else {
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("z");
                    if (timeZones.containsKey(message.getAuthor())) {
                        String zone = formatter.format(ZonedDateTime.now().withZoneSameLocal(
                                timeZones.get(message.getAuthor())));
                        return "Your time zone is currently " + zone;
                    } else {
                        return "You don't have a time zone registered. Do so now! It'll be helpful, trust me.";
                    }
                }
            }
        });

        commands.put("genji", new UserInfoCommand(false, false,
                "!genji",
                "Needs healing.") {
            @Override
            String processUser(Scanner args, User user) throws Exception {
                return "I need healing!";
            }
        });

        class HelpCommand extends UserInfoCommand {
            private boolean revealModOnly;

            private HelpCommand (boolean MOD_ONLY, boolean MUTATOR, String template, String info, boolean revealModOnly) {
                super(MOD_ONLY, MUTATOR, template, info);
                this.revealModOnly = revealModOnly;
            }

            String processUser(Scanner args, User u) throws Exception {
                if (args.hasNext()) {
                    String command = args.next();
                    if (command.length() > prefix.length() && command.substring(0, prefix.length()).equals(prefix)) {
                        command = command.substring(prefix.length());
                    }
                    if (commands.containsKey(command) && commands.get(command).hidden() == revealModOnly) {
                        return commands.get(command).getUsage();
                    } else  {
                        return "No command with that name!";
                    }
                } else {
                    String out = "Here is a list of all commands - use !" + (revealModOnly? "mod":"") +"help " +
                            "[command name] for usage.";
                    for (Map.Entry<String, Command> entry : commands.entrySet()) {
                        if (entry.getValue().hidden() == revealModOnly) {
                            out += "\n - " + entry.getKey();
                        }
                    }
                    return out;
                }
            }
        }

        commands.put("help", new HelpCommand(false, false,
                "!help [optional: command name]",
                "Lists all commands. Use !help [command name] for more detailed usage information.",
                false));

        commands.put("modhelp", new HelpCommand(true, false,
                "!modhelp [optional: command name]",
                "Just like !help, but for super-secret mod-only commands.",
                true) {
            @Override
            boolean hidden() {
                return false;
            }
        });
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        // We don't want to respond to other bot accounts, including us

        String content = event.getMessage().getContentRaw();

        //fast-failure for non-commands
        if (content.length() >= prefix.length() && !content.substring(0, prefix.length()).equals(prefix)) return;

        // split up the message and process it
        Scanner args = new Scanner(content.substring(prefix.length()));
        String command = args.next().toLowerCase();
        if (this.commands.containsKey(command)) {
            boolean backup = this.commands.get(command).execute(args, event, MOD_ID);
            if (backup) {
                this.backup();
            }
        }
        else {
            event.getChannel().sendMessage("Command not recognized - use !help for a list of commands.").queue();
        }
    }

    @Override
    public void onGuildMemberJoin (GuildMemberJoinEvent event) {
        event.getGuild().getSystemChannel().sendMessage("Hello " + event.getUser().getAsMention() + ", Welcome to " +
                "Spark's PUGs! Make sure to read the rules in #read-me-first before anything else. And feel free to " +
                "message Spark or a Moderator to ask about PUGs! \n(Except DragonFire, He's only a mod because he " +
                "writes and maintains me, beep boop.) \nIf you want to learn more about how to properly use me, the " +
                "!help command will provide information on my functionality.").queue();
    }

    @Override
    public void onReady (ReadyEvent event) {
        try {
            this.readFromBackup(event.getJDA());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void backup () {
        try {
            File backup = new File(backupFile);
            ObjectOutputStream output = new ObjectOutputStream(new FileOutputStream(backup));

            LinkedHashMap<String, SerializablePUG> spugs = new LinkedHashMap<>();
            for (Map.Entry<String, PUG> pug : this.pugs.entrySet()) {
                spugs.put(pug.getKey(), pug.getValue().toSerializableForm());
            }

            LinkedHashMap<String, ZoneId> sTimeZones = new LinkedHashMap<>();
            for (Map.Entry<User, ZoneId> userEntry : this.timeZones.entrySet()) {
                sTimeZones.put(userEntry.getKey().getId(), userEntry.getValue());
            }

            output.writeObject(spugs);
            output.writeObject(sTimeZones);
            output.close();
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("WARNING: Could not back up bot! Data may not be saved if bot gets restarted!");
        }
    }

    private void readFromBackup (JDA api) throws Exception {
        this.pugs = new LinkedHashMap<>();
        this.timeZones = new LinkedHashMap<>();

        File backup = new File (backupFile);
        ObjectInputStream input = new ObjectInputStream(new FileInputStream(backup));

        LinkedHashMap<String, SerializablePUG> spugs = (LinkedHashMap<String, SerializablePUG>) input.readObject();
        for (Map.Entry<String, SerializablePUG> spug : spugs.entrySet()) {
            this.pugs.put(spug.getKey(), spug.getValue().toPUG(api));
        }

        LinkedHashMap<String, ZoneId> sTimeZones = (LinkedHashMap<String, ZoneId>) input.readObject();
        for (Map.Entry<String, ZoneId> userEntry : sTimeZones.entrySet()) {
            this.timeZones.put(api.getUserById(userEntry.getKey()), userEntry.getValue());
        }

        input.close();
    }

    private ZoneId parseZone (String zone) throws Exception{
        try {
            DateTimeFormatter zoneIntake = DateTimeFormatter.ofPattern("z");
            return ZoneId.from(zoneIntake.parse(zone.toUpperCase()));
        } catch (Exception e) {
            throw new IllegalCommandArgumentException("Incorrectly formatted time zone - remember to use " +
                    "the three-letter version, I need to be able to distinguish between daylight and " +
                    "standard times.");
        }
    }

    private ZonedDateTime parseTime (String time, User u) throws Exception {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("h[h]:mm a[ z][ M-d][-yyyy]");
        TemporalAccessor parsedTime = formatter.parse(time.toUpperCase());

        ZoneId zone;
        if (parsedTime.isSupported(ChronoField.OFFSET_SECONDS)) {
            zone = ZoneId.from(parsedTime);
        } else {
            if (timeZones.containsKey(u)) {
                zone =  timeZones.get(u);
            } else {
                throw new IllegalCommandArgumentException("No time zone found - register one now with !timezone.");
            }
        }

        ZonedDateTime zonedTime = ZonedDateTime.of(LocalDate.now(), LocalTime.from(parsedTime), zone);

        if (parsedTime.isSupported(ChronoField.MONTH_OF_YEAR)) {
            zonedTime = zonedTime.withMonth(parsedTime.get(ChronoField.MONTH_OF_YEAR));
            zonedTime = zonedTime.withDayOfMonth(parsedTime.get(ChronoField.DAY_OF_MONTH));
            if (parsedTime.isSupported(ChronoField.YEAR)) {
                zonedTime = zonedTime.withYear(parsedTime.get(ChronoField.YEAR));
            }
        }

        return zonedTime;
    }

    private String getPugName(Scanner args) throws Exception{
        String pugName = args.next();
        if (pugs.containsKey(pugName)) {
            return pugName;
        } else {
            throw new IllegalCommandArgumentException("No PUG with that name.");
        }
    }

    private ZoneId getZone (User user, Scanner args) throws Exception {
        if (args.hasNext()) {
            return parseZone(args.next());
        } else {
            if (timeZones.containsKey(user)) {
                return timeZones.get(user);
            } else {
                throw new IllegalCommandArgumentException("No time zone found - register one now with !timezone.");
            }
        }
    }
}