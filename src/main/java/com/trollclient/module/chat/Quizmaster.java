package com.trollclient.module.chat;

import com.trollclient.module.Category;
import com.trollclient.module.Module;
import com.trollclient.setting.BoolSetting;
import com.trollclient.setting.ModeSetting;
import com.trollclient.setting.NumberSetting;
import com.trollclient.setting.TextSetting;
import com.trollclient.util.ChatUtil;
import com.trollclient.util.Targets;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Turns chat into a pub quiz: asks a question, watches for the answer (typos
 * forgiven), keeps score, runs rounds, and takes !hint / !skip / !top from
 * the players. Works in party or guild chat too, through the Channel setting.
 */
public class Quizmaster extends Module {
	private record Question(String text, String... answers) {
	}

	private static final String[] NUMBER_WORDS = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
			"eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty"};

	private static final Question[] MINECRAFT = {
			new Question("how many obsidian blocks does the smallest nether portal frame need (no corners)?", "10"),
			new Question("how many eyes of ender does it take to fill an end portal?", "12"),
			new Question("what mob drops blaze rods?", "blaze"),
			new Question("what mob drops ender pearls?", "enderman", "endermen"),
			new Question("what do you feed a wolf to tame it?", "bone"),
			new Question("what animal do you shear for wool?", "sheep"),
			new Question("what ore lights up when you touch it?", "redstone"),
			new Question("what do you get when you smelt sand?", "glass"),
			new Question("what do you get when you smelt cobblestone?", "stone"),
			new Question("what's the strongest tool material?", "netherite"),
			new Question("what metal do piglins love?", "gold"),
			new Question("what ore only spawns in mountains?", "emerald"),
			new Question("what do villagers use as money?", "emerald"),
			new Question("how many slots does the hotbar have?", "9"),
			new Question("how many ender pearls fit in one stack?", "16"),
			new Question("which enchantment repairs your gear with xp?", "mending"),
			new Question("which enchantment brings a thrown trident back?", "loyalty"),
			new Question("which boots enchantment freezes water under you?", "frost walker"),
			new Question("what do pandas eat?", "bamboo"),
			new Question("what do you right-click a cow with to get milk?", "bucket"),
			new Question("which boss do you build out of soul sand and wither skeleton skulls?", "wither"),
			new Question("which boss lives in the end?", "ender dragon", "dragon"),
			new Question("what do you call a baby zombie riding a chicken?", "chicken jockey"),
			new Question("what block soaks up water?", "sponge"),
			new Question("what green mob hisses before it explodes?", "creeper"),
			new Question("what block do you need to build a nether portal?", "obsidian"),
			new Question("which mob picks up blocks and carries them around?", "enderman"),
			new Question("what do you sleep in to skip the night?", "bed"),
			new Question("what item do you use to collect honey from a hive?", "glass bottle", "bottle"),
			new Question("what tool do you use to collect honeycomb?", "shears"),
			new Question("what crop do you need three of to make bread?", "wheat"),
			new Question("what flying hostile mob comes for you if you skip sleep for days?", "phantom"),
			new Question("which mob drops a totem of undying?", "evoker"),
			new Question("what item lets you glide through the air?", "elytra"),
			new Question("what do you throw to find a stronghold?", "eye of ender", "ender eye"),
			new Question("what do you light a nether portal with?", "flint and steel", "fire charge"),
			new Question("what does a pig turn into when it's struck by lightning?", "zombified piglin", "zombie pigman", "pigman"),
			new Question("what does a villager turn into when it's struck by lightning?", "witch"),
			new Question("how many iron blocks does an iron golem need?", "4"),
			new Question("what's the highest level of sharpness?", "5", "v"),
			new Question("how many game ticks are there in a second?", "20"),
			new Question("what plant do you smelt into green dye?", "cactus"),
			new Question("what do you smelt logs into for a coal substitute?", "charcoal"),
			new Question("how many diamonds does a full set of diamond armour cost?", "24"),
			new Question("what do you tame a cat with?", "fish", "cod", "salmon"),
			new Question("what colour are creepers?", "green"),
	};

	private static final Question[] GENERAL = {
			new Question("what planet is known as the red planet?", "mars"),
			new Question("what's the biggest planet in the solar system?", "jupiter"),
			new Question("which planet has the famous rings?", "saturn"),
			new Question("how many legs does a spider have?", "8"),
			new Question("what's the capital of france?", "paris"),
			new Question("what's the capital of japan?", "tokyo"),
			new Question("what's the capital of italy?", "rome"),
			new Question("what's the capital of australia?", "canberra"),
			new Question("what's the capital of canada?", "ottawa"),
			new Question("what's the largest ocean on earth?", "pacific"),
			new Question("how many sides does a hexagon have?", "6"),
			new Question("what gas do plants take in from the air?", "carbon dioxide", "co2"),
			new Question("what's the chemical symbol for gold?", "au"),
			new Question("what's the chemical formula for water?", "h2o"),
			new Question("how many continents are there?", "7"),
			new Question("what's the fastest land animal?", "cheetah"),
			new Question("what's the tallest animal?", "giraffe"),
			new Question("what's the largest animal ever?", "blue whale", "whale"),
			new Question("how many minutes are in a day?", "1440"),
			new Question("how many hours are in a week?", "168"),
			new Question("what's the hardest natural substance?", "diamond"),
			new Question("what colour do you get mixing blue and yellow?", "green"),
			new Question("how many strings does a standard guitar have?", "6"),
			new Question("what's the smallest prime number?", "2"),
			new Question("at how many degrees celsius does water boil at sea level?", "100"),
			new Question("how many bones are in an adult human body?", "206"),
			new Question("who painted the mona lisa?", "leonardo da vinci", "da vinci", "leonardo"),
			new Question("how many players does a football (soccer) team have on the pitch?", "11"),
	};

	private final ModeSetting topic = add(new ModeSetting("Topic", "What the questions are about", "Mixed",
			"Mixed", "Minecraft", "General", "Maths", "Custom"));
	private final TextSetting customQuestions = add(new TextSetting("Custom Questions",
			"Your own questions, split with |. Write them as question = answer, other accepted answer",
			"what's my favourite block? = dirt | who made this quiz? = me", 2048))
			.visibleWhen(() -> topic.is("Custom") || topic.is("Mixed"));
	private final NumberSetting interval = add(new NumberSetting("Interval", "Time between questions", 45, 10, 600, 5).unit("s"));
	private final NumberSetting answerTime = add(new NumberSetting("Answer Time", "How long everyone gets to answer", 30, 10, 120, 5)
			.unit("s"));
	private final ModeSetting hints = add(new ModeSetting("Hints", "Letters given away as time runs out", "Two", "Off", "One", "Two"));
	private final BoolSetting forgiving = add(new BoolSetting("Forgive Typos", "Accept answers that are a letter or two off", true));
	private final ModeSetting scoring = add(new ModeSetting("Scoring", "Points for a right answer", "Speed Bonus",
			"One Point", "Speed Bonus"));
	private final NumberSetting roundLength = add(new NumberSetting("Round Length", "Questions per round, then a winner is crowned (0 = endless)",
			10, 0, 50, 1));
	private final BoolSetting stopAfterRound = add(new BoolSetting("Stop After Round", "Switch off when a round ends instead of starting another", false))
			.visibleWhen(() -> roundLength.getInt() > 0);
	private final NumberSetting minPlayers = add(new NumberSetting("Min Players", "Only ask when at least this many other players are online", 1, 0, 20, 1));
	private final BoolSetting commands = add(new BoolSetting("Player Commands", "Players can use !hint, !skip, !score, !top and !question", true));
	private final TextSetting commandPrefix = add(new TextSetting("Command Prefix", "What players type before those commands", "!", 3))
			.visibleWhen(commands::get);
	private final NumberSetting skipVotes = add(new NumberSetting("Skip Votes", "How many players have to !skip a question", 2, 1, 10, 1))
			.visibleWhen(commands::get);
	private final TextSetting channel = add(new TextSetting("Channel", "Command in front of every quiz line, e.g. /pc for party chat or /gc for guild chat (blank = public)",
			"", 24).placeholder("public chat"));
	private final TextSetting tag = add(new TextSetting("Tag", "Put in front of everything the quizmaster says", "[quiz]", 16)
			.placeholder("none"));
	private final NumberSetting gap = add(new NumberSetting("Message Gap", "Minimum time between quiz lines (servers kick fast talkers)", 1500, 500, 5000, 100)
			.unit("ms"));

	private final Map<String, Integer> points = new HashMap<>();
	private final Map<String, Deque<Question>> decks = new HashMap<>();
	private final Set<String> skippers = new HashSet<>();
	/** Lines wait here so a winner and the next question never go out in the same tick. */
	private final List<String> outbox = new ArrayList<>();
	private Question current;
	/** Every accepted spelling of the current answer, normalised. */
	private Set<String> accepted = Set.of();
	/** The question is in the outbox until this flips: answers before it was even asked don't count. */
	private boolean live;
	private long askedAt;
	private long lastEnded;
	private long lastSent;
	private long lastHintCommand;
	private int hintsGiven;
	private int asked;
	private int inRound;
	private String lastWinner;
	private int winStreak;
	private String customParsedFrom;
	/** The question as it sits in the outbox, so we know when it's actually been said. */
	private String questionLine;
	private boolean stopWhenQuiet;
	private List<Question> custom = List.of();

	public Quizmaster() {
		super("Quizmaster", "Hosts a trivia quiz in chat: questions, hints, typo-proof answers, rounds, scores and !commands for players.",
				Category.CHAT);
	}

	@Override
	protected void onEnable() {
		reset();
		points.clear();
		inRound = 0;
		asked = 0;
		stopWhenQuiet = false;
		// first question a few seconds after switching on, not a whole interval later
		lastEnded = System.currentTimeMillis() - (long) (interval.get() * 1000) + 3000;
	}

	@Override
	public void onWorldLeave() {
		reset();
	}

	private void reset() {
		current = null;
		live = false;
		outbox.clear();
		skippers.clear();
		lastWinner = null;
		winStreak = 0;
	}

	@Override
	public void onTick() {
		long now = System.currentTimeMillis();
		if (!outbox.isEmpty() && now - lastSent >= gap.getInt()) {
			lastSent = now;
			String line = outbox.remove(0);
			String via = channel.get().trim();
			ChatUtil.send(via.isEmpty() ? line : via + " " + line);
			if (current != null && !live && line.equals(questionLine)) {
				// the clock starts once people can actually read the question
				live = true;
				askedAt = now;
			}
		}
		if (stopWhenQuiet && outbox.isEmpty()) {
			setEnabled(false);
			return;
		}
		if (current == null) {
			if (outbox.isEmpty() && now - lastEnded >= interval.get() * 1000 && enoughPlayers()) {
				ask();
			}
			return;
		}
		if (!live) {
			return;
		}
		float elapsed = (now - askedAt) / (answerTime.getFloat() * 1000);
		int wanted = hints.is("Off") ? 0 : elapsed >= 0.75f && hints.is("Two") ? 2 : elapsed >= 0.5f ? 1 : 0;
		if (hintsGiven < wanted) {
			giveHint();
		}
		if (elapsed >= 1f) {
			say("time's up! the answer was " + current.answers()[0]);
			lastWinner = null;
			winStreak = 0;
			end();
		}
	}

	private boolean enoughPlayers() {
		return Targets.onlineNames().size() >= minPlayers.getInt();
	}

	@Override
	public void onChatMessage(String sender, String message) {
		String said = message.trim();
		String prefix = commandPrefix.get().trim();
		if (commands.get() && !prefix.isEmpty() && said.startsWith(prefix)) {
			command(sender, said.substring(prefix.length()).trim().toLowerCase(Locale.ROOT));
			return;
		}
		if (current == null || !live) {
			return;
		}
		if (isCorrect(said)) {
			int earned = scoring.is("Speed Bonus") ? Math.max(1, 3 - hintsGiven) : 1;
			int score = points.merge(sender, earned, Integer::sum);
			winStreak = sender.equals(lastWinner) ? winStreak + 1 : 1;
			lastWinner = sender;
			String streak = winStreak >= 3 ? ", " + winStreak + " in a row!" : "";
			say(sender + " got it! the answer was " + current.answers()[0] + " (+" + earned + ", " + score + " total" + streak + ")");
			end();
		}
	}

	private void command(String sender, String cmd) {
		switch (cmd) {
			case "hint" -> {
				long now = System.currentTimeMillis();
				if (current != null && live && hintsGiven < 2 && now - lastHintCommand > 10_000) {
					lastHintCommand = now;
					giveHint();
				}
			}
			case "skip" -> {
				if (current == null || !live || !skippers.add(sender)) {
					return;
				}
				int need = skipVotes.getInt();
				if (skippers.size() >= need) {
					say("skipped! the answer was " + current.answers()[0]);
					end();
				} else {
					say(sender + " wants to skip (" + skippers.size() + "/" + need + ", type " + commandPrefix.get().trim() + "skip)");
				}
			}
			case "score", "points" -> say(sender + " has " + points.getOrDefault(sender, 0) + " point" + (points.getOrDefault(sender, 0) == 1 ? "" : "s"));
			case "top", "scores", "leaderboard" -> say(points.isEmpty() ? "no points yet" : "top: " + leaderboard());
			case "question", "q", "repeat" -> {
				if (current != null && live) {
					say("Q" + asked + ": " + current.text());
				}
			}
			default -> {
			}
		}
	}

	private void ask() {
		Question q = next();
		if (q == null) {
			return;
		}
		current = q;
		accepted = spellings(q);
		live = false;
		hintsGiven = 0;
		skippers.clear();
		asked++;
		inRound++;
		String count = roundLength.getInt() > 0 ? " (" + inRound + "/" + roundLength.getInt() + ")" : "";
		say("Q" + asked + count + ": " + q.text());
		questionLine = outbox.get(outbox.size() - 1);
	}

	private Question next() {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		List<String> topics = new ArrayList<>();
		if (topic.is("Mixed")) {
			topics.add("Minecraft");
			topics.add("General");
			topics.add("Maths");
			if (!customList().isEmpty()) {
				topics.add("Custom");
			}
		} else {
			topics.add(topic.get());
		}
		String t = topics.get(rnd.nextInt(topics.size()));
		return switch (t) {
			case "Maths" -> maths(rnd);
			case "Minecraft" -> draw(t, List.of(MINECRAFT));
			case "General" -> draw(t, List.of(GENERAL));
			default -> draw(t, customList());
		};
	}

	/** Deals from a shuffled deck, so nothing comes up twice until everything has come up once. */
	private Question draw(String name, List<Question> bank) {
		if (bank.isEmpty()) {
			return null;
		}
		Deque<Question> deck = decks.computeIfAbsent(name, k -> new ArrayDeque<>());
		deck.removeIf(q -> !bank.contains(q));
		if (deck.isEmpty()) {
			List<Question> shuffled = new ArrayList<>(bank);
			Collections.shuffle(shuffled);
			if (shuffled.size() > 1 && shuffled.get(0) == current) {
				Collections.swap(shuffled, 0, 1);
			}
			deck.addAll(shuffled);
		}
		return deck.poll();
	}

	/** "question = answer, other answer | ..." from the setting, re-read only when it changes. */
	private List<Question> customList() {
		String setting = customQuestions.get();
		if (setting.equals(customParsedFrom)) {
			return custom;
		}
		List<Question> list = new ArrayList<>();
		for (String entry : setting.split("\\|")) {
			int eq = entry.lastIndexOf('=');
			if (eq <= 0) {
				continue;
			}
			String text = entry.substring(0, eq).trim();
			String[] answers = entry.substring(eq + 1).split(",");
			List<String> clean = new ArrayList<>();
			for (String a : answers) {
				if (!a.isBlank()) {
					clean.add(a.trim().toLowerCase(Locale.ROOT));
				}
			}
			if (!text.isEmpty() && !clean.isEmpty()) {
				list.add(new Question(text, clean.toArray(String[]::new)));
			}
		}
		custom = list;
		customParsedFrom = setting;
		return list;
	}

	private static Question maths(ThreadLocalRandom rnd) {
		int a;
		int b;
		return switch (rnd.nextInt(5)) {
			case 0 -> {
				a = rnd.nextInt(12, 99);
				b = rnd.nextInt(12, 99);
				yield new Question("what's " + a + " + " + b + "?", Integer.toString(a + b));
			}
			case 1 -> {
				a = rnd.nextInt(50, 200);
				b = rnd.nextInt(10, a);
				yield new Question("what's " + a + " - " + b + "?", Integer.toString(a - b));
			}
			case 2 -> {
				a = rnd.nextInt(3, 13);
				b = rnd.nextInt(3, 13);
				yield new Question("what's " + a + " x " + b + "?", Integer.toString(a * b));
			}
			case 3 -> {
				b = rnd.nextInt(2, 13);
				a = b * rnd.nextInt(2, 13);
				yield new Question("what's " + a + " / " + b + "?", Integer.toString(a / b));
			}
			default -> {
				a = rnd.nextInt(2, 16);
				yield new Question("what's " + a + " squared?", Integer.toString(a * a));
			}
		};
	}

	// ------------------------------------------------------------------ answers

	/** Lowercase, punctuation to spaces, single spaces: "The Creeper!!" -> "the creeper". */
	private static String normalise(String s) {
		return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
	}

	/** Every way of writing an answer we'll take: digits and words, with or without "the", singular or plural. */
	private static Set<String> spellings(Question q) {
		Set<String> out = new LinkedHashSet<>();
		for (String raw : q.answers()) {
			String a = normalise(raw);
			if (a.isEmpty()) {
				continue;
			}
			out.add(a);
			out.add(a.replaceFirst("^(the|a|an) ", ""));
			if (a.matches("\\d+") && Integer.parseInt(a) < NUMBER_WORDS.length) {
				out.add(NUMBER_WORDS[Integer.parseInt(a)]);
			}
			for (int i = 0; i < NUMBER_WORDS.length; i++) {
				if (a.equals(NUMBER_WORDS[i])) {
					out.add(Integer.toString(i));
				}
			}
			if (!a.matches("\\d+")) {
				out.add(a.endsWith("s") ? a.substring(0, a.length() - 1) : a + "s");
			}
		}
		out.removeIf(String::isBlank);
		return out;
	}

	/** The answer appears in the message as whole words, or (if forgiving) within a letter or two of it. */
	private boolean isCorrect(String message) {
		String said = normalise(message);
		if (said.isEmpty()) {
			return false;
		}
		String padded = " " + said + " ";
		String[] words = said.split(" ");
		for (String answer : accepted) {
			if (padded.contains(" " + answer + " ")) {
				return true;
			}
			if (!forgiving.get() || answer.length() < 5 || answer.matches("[\\d ]+")) {
				continue;
			}
			int allowed = answer.length() >= 9 ? 2 : 1;
			int span = answer.split(" ").length;
			for (int i = 0; i + span <= words.length; i++) {
				String chunk = String.join(" ", java.util.Arrays.copyOfRange(words, i, i + span));
				if (Math.abs(chunk.length() - answer.length()) <= allowed && distance(chunk, answer) <= allowed) {
					return true;
				}
			}
		}
		return false;
	}

	/** Levenshtein distance. */
	private static int distance(String a, String b) {
		int[] prev = new int[b.length() + 1];
		int[] cur = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			prev[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			cur[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
			}
			int[] t = prev;
			prev = cur;
			cur = t;
		}
		return prev[b.length()];
	}

	// ------------------------------------------------------------------ hints and endings

	private void giveHint() {
		hintsGiven++;
		String answer = current.answers()[0];
		say("hint: " + (answer.matches("\\d+") ? numberHint(answer) : letters(answer, hintsGiven)));
	}

	/** Numbers get a range instead of letters: "it has 3 digits", then "it's between 140 and 160". */
	private static String numberHint(String answer) {
		int n = Integer.parseInt(answer);
		if (n < 10) {
			return "it's a single digit";
		}
		int lo = (n / 10) * 10;
		return "it's between " + lo + " and " + (lo + 10);
	}

	/** First hint shows the first letter of each word, the second every other letter too. */
	private static String letters(String answer, int level) {
		StringBuilder sb = new StringBuilder();
		boolean wordStart = true;
		for (int i = 0; i < answer.length(); i++) {
			char c = answer.charAt(i);
			boolean show = c == ' ' || wordStart || (level >= 2 && i % 2 == 0);
			sb.append(show ? c : '_');
			wordStart = c == ' ';
		}
		return sb.toString();
	}

	private void end() {
		current = null;
		live = false;
		lastEnded = System.currentTimeMillis();
		int length = roundLength.getInt();
		if (length > 0 && inRound >= length) {
			if (points.isEmpty()) {
				say("round over! nobody scored. tough crowd");
			} else {
				String winner = points.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
				say("round over! " + winner + " wins. final scores: " + leaderboard());
			}
			points.clear();
			inRound = 0;
			if (stopAfterRound.get()) {
				// let the outbox finish before switching off
				stopWhenQuiet = true;
			}
		} else if (asked % 5 == 0 && !points.isEmpty()) {
			say("scores: " + leaderboard());
		}
	}

	private String leaderboard() {
		return points.entrySet().stream()
				.sorted((x, y) -> y.getValue() - x.getValue())
				.limit(3)
				.map(e -> e.getKey() + " " + e.getValue())
				.collect(Collectors.joining(", "));
	}

	private void say(String line) {
		String prefix = tag.get().isBlank() ? "" : tag.get().trim() + " ";
		outbox.add(prefix + line);
	}

	@Override
	public String getInfo() {
		return asked > 0 ? "Q" + asked : topic.get();
	}
}
