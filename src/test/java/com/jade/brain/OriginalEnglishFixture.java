package com.jade.brain;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Original synthetic English, authored for this benchmark; not representative of real-world prose. */
public final class OriginalEnglishFixture {
    private OriginalEnglishFixture() {}
    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Supply the local fixture output directory");
        Path root = Path.of(args[0]); Files.createDirectories(root);
        String[] names = {"anna", "ben", "clara", "daniel", "ella", "felix", "grace", "henry", "iris", "james",
                "kate", "leo", "maya", "noah", "olivia", "peter", "quinn", "rose", "sara", "tom"};
        String[] places = {"garden", "library", "workshop", "museum", "kitchen", "harbor", "school", "station", "farm", "bakery",
                "market", "theater", "laboratory", "office", "park", "mill", "studio", "observatory", "clinic", "greenhouse"};
        String[] objects = {"flowers", "books", "tools", "maps", "recipes", "boats", "lessons", "tickets", "seeds", "loaves",
                "baskets", "costumes", "samples", "letters", "trees", "wheels", "paintings", "telescopes", "bandages", "plants"};
        for (int i = 0; i < names.length; i++) {
            String n = names[i], p = places[i], o = objects[i];
            String text = "the " + p + " was quiet when " + n + " arrived on monday morning.\n"
                    + n + " put a notebook on the table and opened the nearest window.\n"
                    + "a small bird landed outside. the sky was clear, but the road was still wet from last night's rain.\n"
                    + "this was the first day of a new project. the team wanted to make the " + p + " easier for visitors to use.\n"
                    + "the computer displayed a list of " + o + ". each item had a name, a date, and a short description.\n"
                    + n + " checked the list carefully. one description was missing, and two dates were incorrect.\n"
                    + "\"what should we do first?\" asked a colleague. \"let's fix the simple mistakes before we add anything new.\"\n"
                    + "they didn't hurry. they compared each record with a note written by the person who had created it.\n"
                    + "by 10:30, the team had corrected 12 records and saved a copy of the original list.\n"
                    + "the work became easier after they agreed on a clear method. a careful question often saved an hour of guessing.\n"
                    + "jade was the name of their experimental computer program. it learned patterns from examples, not from magic.\n"
                    + "the program sometimes made strange suggestions. \"we can't trust every answer,\" " + n + " said.\n"
                    + "this reminded everyone to check the evidence. a confident sentence could still describe the wrong thing.\n"
                    + "at lunch, they sat near the door and shared bread, apples, and a pot of tea.\n"
                    + "once upon a time, the " + p + " had been a small room with only a wooden table. now it served the whole town.\n"
                    + "in the afternoon, " + n + " helped a visitor find information about the " + o + ".\n"
                    + "the visitor asked three questions. the first was easy; the second needed a book; the third had no clear answer.\n"
                    + "\"i don't know yet,\" " + n + " replied. \"we can look at the records together tomorrow.\"\n"
                    + "when evening came, the team closed the windows, checked the lights, and wrote down the next day's tasks.\n"
                    + "the project was unfinished, but it was a little more useful than it had been that morning.\n";
            Files.writeString(root.resolve(String.format("%02d-%s.txt", i + 1, p)), text, StandardCharsets.UTF_8);
        }
    }
}
