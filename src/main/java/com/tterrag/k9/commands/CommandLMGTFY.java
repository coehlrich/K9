package com.tterrag.k9.commands;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import com.google.common.base.Charsets;
import com.tterrag.k9.commands.api.Argument;
import com.tterrag.k9.commands.api.Command;
import com.tterrag.k9.commands.api.CommandBase;
import com.tterrag.k9.commands.api.CommandContext;
import com.tterrag.k9.commands.api.Flag;
import com.tterrag.k9.util.annotation.Nullable;

import discord4j.common.util.Snowflake;
import reactor.core.publisher.Mono;

@Command
public class CommandLMGTFY extends CommandBase {
    
    private static final Flag FLAG_IE = new SimpleFlag('e', "internet-explain", "Enable internet explainer mode.", false);
    
    private static final Argument<String> ARG_QUERY = new SentenceArgument("query", "The query to google.", true);

    public CommandLMGTFY() {
        super("lmgtfy", false);
    }
    
    @Override
    public Mono<?> process(CommandContext ctx) {
        int iie = ctx.getFlags().containsKey(FLAG_IE) ? 1 : 0;
        StringBuilder url = new StringBuilder("<http://lmgtfy.com/?iie=").append(iie).append("&q=");
        String arg = ctx.getArg(ARG_QUERY);
        return Mono.defer(() -> {
            try {
                return ctx.reply(url.append(URLEncoder.encode(arg, Charsets.UTF_8.name())).append(">").toString());
            } catch (UnsupportedEncodingException e) {
                return ctx.error(e);
            }
        });
    }
    
    @Override
    public String getDescription(@Nullable Snowflake guildId) {
        return "Google something for someone.";
    }
}
