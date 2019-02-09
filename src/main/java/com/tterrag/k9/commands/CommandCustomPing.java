package com.tterrag.k9.commands;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.tterrag.k9.commands.CommandCustomPing.CustomPing;
import com.tterrag.k9.commands.api.Argument;
import com.tterrag.k9.commands.api.Command;
import com.tterrag.k9.commands.api.CommandContext;
import com.tterrag.k9.commands.api.CommandPersisted;
import com.tterrag.k9.commands.api.Flag;
import com.tterrag.k9.util.ListMessageBuilder;
import com.tterrag.k9.util.Patterns;
import com.tterrag.k9.util.annotation.NonNull;

import discord4j.core.DiscordClient;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.GuildChannel;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.PrivateChannel;
import discord4j.core.object.entity.User;
import discord4j.core.object.util.Permission;
import discord4j.core.object.util.Snowflake;
import lombok.Value;
import reactor.core.publisher.Mono;

@Command
public class CommandCustomPing extends CommandPersisted<Map<Long, List<CustomPing>>> {
    
    @Value
    public static class CustomPing {
        Pattern pattern;
        String text;
    }
    
    private class PingListener {
        
        public void onMessageRecieved(MessageCreateEvent event) {
            checkCustomPing(event.getMessage());
        }
        
        private void checkCustomPing(Message msg) {
            if (msg.getAuthor() == null || msg.getChannel().block() instanceof PrivateChannel || msg.getAuthor().filter(a -> a.getId().equals(msg.getClient().getSelfId().get())).isPresent()) return;
            
            Multimap<Long, CustomPing> pings = HashMultimap.create();
            CommandCustomPing.this.getPingsForGuild(msg.getGuild().block()).forEach(pings::putAll);
            for (Entry<Long, CustomPing> e : pings.entries()) {
                if (e.getKey() == msg.getAuthor().get().getId().asLong()) {
                    continue;
                }
                Member owner = msg.getGuild().block().getMemberById(Snowflake.of(e.getKey())).block();
                if (owner == null || !msg.getChannel().ofType(GuildChannel.class).block().getEffectivePermissions(owner.getId()).block().contains(Permission.VIEW_CHANNEL)) {
                    continue;
                }
                Matcher matcher = e.getValue().getPattern().matcher(msg.getContent().get());
                if (matcher.find()) {
                    owner.getPrivateChannel()
                         .flatMap(c -> c.createMessage($ -> $.setEmbed(embed -> embed
                                .setAuthor("New ping from: " + msg.getAuthorAsMember().block().getDisplayName(), msg.getAuthor().get().getAvatarUrl(), null)
                                .addField(e.getValue().getText(), msg.getContent().get(), true)
                                .addField("Link", String.format("https://discordapp.com/channels/%d/%d/%d", msg.getGuild().block().getId().asLong(), msg.getChannelId().asLong(), msg.getId().asLong()), true))))
                         .subscribe();
                }
            }
        }
    }
    
    @NonNull
    public static final String NAME = "ping";
    
    private static final Flag FLAG_ADD = new SimpleFlag('a', "add", "Adds a new custom ping.", false);
    private static final Flag FLAG_RM = new SimpleFlag('r', "remove", "Removes a custom ping by its pattern.", true);
    private static final Flag FLAG_LS = new SimpleFlag('l', "list", "Lists your pings for this guild.", false);

    private static final Argument<String> ARG_PATTERN = new WordArgument("pattern", "The regex pattern to match messages against for a ping to be sent to you.", true) {
        @Override
        public Pattern pattern() {
            return Patterns.REGEX_PATTERN;
        }
        
        @Override
        public boolean required(Collection<Flag> flags) {
           return flags.contains(FLAG_ADD);
        }
    };
    
    private static final Argument<String> ARG_TEXT = new SentenceArgument("pingtext", "The text to use in the ping.", false);

    public CommandCustomPing() {
        super(NAME, false, HashMap::new);
    }
    
    @Override
    public void onRegister(DiscordClient client) {
        super.onRegister(client);
        client.getEventDispatcher().on(MessageCreateEvent.class).subscribe(new PingListener()::onMessageRecieved);
    }
    
    @Override
    public void gatherParsers(GsonBuilder builder) {
        builder.registerTypeAdapter(Pattern.class, (JsonDeserializer<Pattern>) (json, typeOfT, context) -> {
            if (json.isJsonObject()) {
                String pattern = json.getAsJsonObject().get("pattern").getAsString();
                int flags = json.getAsJsonObject().get("flags").getAsInt();
                return Pattern.compile(pattern, flags);
            }
            throw new JsonParseException("Pattern must be an object");
        });
    }
    
    public Map<Long, List<CustomPing>> getPingsForGuild(Guild guild) {
        if (storage == null) {
            return Collections.emptyMap();
        }
        return storage.get(guild);
    }

    @Override
    public Mono<?> process(CommandContext ctx) {
        if (!ctx.getGuildId().isPresent()) {
            return ctx.error("Custom pings are not available in DMs.");
        }
        User author = ctx.getMessage().getAuthor().orElse(null);
        if (author == null) {
            return ctx.error("Cannot add custom ping without an author.");
        }
        long authorId = author.getId().asLong();
        if (ctx.hasFlag(FLAG_LS)) {
            return storage.get(ctx)
                    .map(data -> data.getOrDefault(authorId, Collections.emptyList()))
                    .flatMap(pings -> new ListMessageBuilder<CustomPing>("custom pings")
                        .addObjects(pings)
                        .indexFunc((p, i) -> i) // 0-indexed
                        .stringFunc(p -> "`/" + p.getPattern().pattern() + "/` | " + p.getText())
                        .build(ctx)
                        .send());
        } else if (ctx.hasFlag(FLAG_ADD)) {
            Matcher matcher = Patterns.REGEX_PATTERN.matcher(ctx.getArg(ARG_PATTERN));
            matcher.find();
            Pattern pattern = Pattern.compile(matcher.group(1));
            
            String text = ctx.getArgOrElse(ARG_TEXT, "You have a new ping!");
            CustomPing ping = new CustomPing(pattern, text);
            
            // Lie a bit, do this first so it doesn't ping for itself
            return ctx.reply("Added a new custom ping for the pattern: `" + pattern + "`")
                      .then(storage.get(ctx))
                      .doOnNext(data -> data.computeIfAbsent(authorId, $ -> new ArrayList<>()).add(ping));
        } else if (ctx.hasFlag(FLAG_RM)) {
            return storage.get(ctx)
                    .map(data -> data.getOrDefault(authorId, Collections.emptyList())) // Try to remove by pattern
                    .filter(data -> data.removeIf(ping -> ping.getPattern().pattern().equals(ctx.getFlag(FLAG_RM))))
                    .flatMap($ -> ctx.reply("Deleted ping(s)."))
                    .switchIfEmpty( // If none were removed, try to remove by ID
                            storage.get(ctx)
                                   .map(data -> data.getOrDefault(authorId, Collections.emptyList()))
                                   .flatMap(pings -> {
                                            int idx = Integer.parseInt(ctx.getFlag(FLAG_RM));
                                            if (idx < 0 || idx >= pings.size()) {
                                                return ctx.error("Ping index out of range!");
                                            }
                                            CustomPing removed = pings.remove(idx);
                                            return ctx.reply("Removed ping: " + removed.getPattern().pattern());
                                   })
                                   .onErrorResume(NumberFormatException.class, e -> ctx.error("Found no pings to delete!")));
        }
        return ctx.error("No action to perform.");
    }
    
    @Override
    public String getDescription() {
        return "Use this command to create a custom ping, when any message is sent in this guild that matches the given regex, you will be notified via PM.";
    }

    @Override
    protected TypeToken<Map<Long, List<CustomPing>>> getDataType() {
        return new TypeToken<Map<Long, List<CustomPing>>>(){};
    }
}
