/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.mixin.TextHandlerAccessor;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.font.TextHandler;
import net.minecraft.item.*;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.network.packet.c2s.play.BookUpdateC2SPacket;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.IntStream;

public class BookBot extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("What kind of text to write.")
        .defaultValue(Mode.Random)
        .build()
    );

    private final Setting<Integer> pages = sgGeneral.add(new IntSetting.Builder()
        .name("pages")
        .description("The number of pages to write per book.")
        .defaultValue(50)
        .range(1, 100)
        .sliderRange(1, 100)
        .visible(() -> mode.get() != Mode.File)
        .build()
    );

    private final Setting<Integer> bytes = sgGeneral.add(new IntSetting.Builder()
        .name("bytes")
        .description("The number of bytes to write per page.")
        .defaultValue(1024)
        .range(0, 1280)
        .sliderRange(0, 1280)
        .visible(() -> mode.get() != Mode.File)
        .build()
    );

    private final Setting<Integer> maxCharWidth = sgGeneral.add(new IntSetting.Builder()
        .name("max-char-width")
        .description("Max width for multibyte chars")
        .defaultValue(4)
        .range(2, 8)
        .sliderRange(2, 8)
        .visible(() -> mode.get() != Mode.File)
        .build()
    );

    private final Setting<Integer> lines = sgGeneral.add(new IntSetting.Builder()
        .name("lines")
        .description("The number of lines to write per page.")
        .defaultValue(14)
        .range(0, 14)
        .sliderRange(0, 14)
        .visible(() -> mode.get() != Mode.File)
        .build()
    );

    private final Setting<Boolean> onlyAscii = sgGeneral.add(new BoolSetting.Builder()
        .name("ascii-only")
        .description("Only uses the characters in the ASCII charset.")
        .defaultValue(false)
        .visible(() -> mode.get() == Mode.Random)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("The amount of delay between writing books.")
        .defaultValue(20)
        .min(1)
        .sliderRange(1, 200)
        .build()
    );

    private final Setting<Boolean> sign = sgGeneral.add(new BoolSetting.Builder()
        .name("sign")
        .description("Whether to sign the book.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> name = sgGeneral.add(new StringSetting.Builder()
        .name("name")
        .description("The name you want to give your books.")
        .defaultValue("Meteor on Crack!")
        .visible(sign::get)
        .build()
    );

    private final Setting<Boolean> count = sgGeneral.add(new BoolSetting.Builder()
        .name("append-count")
        .description("Whether to append the number of the book to the title.")
        .defaultValue(true)
        .visible(sign::get)
        .build()
    );

    private File file = new File(MeteorClient.FOLDER, "bookbot.txt");
    private final PointerBuffer filters;

    private int delayTimer, bookCount;
    private Random random;

    public BookBot() {
        super(Categories.Misc, "book-bot", "Automatically writes in books.");

        if (!file.exists()) {
            file = null;
        }

        filters = BufferUtils.createPointerBuffer(1);

        ByteBuffer txtFilter = MemoryUtil.memASCII("*.txt");

        filters.put(txtFilter);
        filters.rewind();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WHorizontalList list = theme.horizontalList();

        WButton selectFile = list.add(theme.button("Select File")).widget();

        WLabel fileName = list.add(theme.label((file != null && file.exists()) ? file.getName() : "No file selected.")).widget();

        selectFile.action = () -> {
            String path = TinyFileDialogs.tinyfd_openFileDialog(
                "Select File",
                new File(MeteorClient.FOLDER, "bookbot.txt").getAbsolutePath(),
                filters,
                null,
                false
            );

            if (path != null) {
                file = new File(path);
                fileName.set(file.getName());
            }
        };

        return list;
    }

    @Override
    public void onActivate() {
        if ((file == null || !file.exists()) && mode.get() == Mode.File) {
            info("No file selected, please select a file in the GUI.");
            toggle();
            return;
        }

        random = new Random();
        delayTimer = delay.get();
        bookCount = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        Predicate<ItemStack> bookPredicate = i ->
            i.getItem() == Items.WRITABLE_BOOK && (i.getNbt() == null || i.getNbt().get("pages") == null || ((NbtList) i.getNbt().get("pages")).isEmpty());

        FindItemResult writableBook = InvUtils.find(bookPredicate);

        // Check if there is a book to write
        if (!writableBook.found()) {
            toggle();
            return;
        }

        // Move the book into hand
        if (!InvUtils.testInMainHand(bookPredicate)) {
            InvUtils.move().from(writableBook.slot()).toHotbar(mc.player.getInventory().selectedSlot);
            return;
        }

        // Check delay
        if (delayTimer > 0) {
            delayTimer--;
            return;
        }

        // Reset delay
        delayTimer = delay.get();

        // Write book

        if (mode.get() == Mode.Random) {
            int origin = 0x21;
            int bound = onlyAscii.get() ? 0x7E : 0x10FFFF;

            writeBook(
                // Generate a random load of ints to use as random characters
                random.ints(origin, bound)
                    .filter(i -> !Character.isWhitespace(i) && i != '\r' && i != '\n')
                    .iterator(),
                true
            );
        } else if (mode.get() == Mode.File) {
            // Ignore if somehow the file got deleted
            if ((file == null || !file.exists()) && mode.get() == Mode.File) {
                info("No file selected, please select a file in the GUI.");
                toggle();
                return;
            }

            // Handle the file being empty
            if (file.length() == 0) {
                MutableText message = Text.literal("");
                message.append(Text.literal("The bookbot file is empty! ").formatted(Formatting.RED));
                message.append(Text.literal("Click here to edit it.")
                    .setStyle(Style.EMPTY
                            .withFormatting(Formatting.UNDERLINE, Formatting.RED)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, file.getAbsolutePath()))
                    )
                );
                info(message);
                toggle();
                return;
            }

            // Read each line of the file and construct a string with the needed line breaks
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                StringBuilder file = new StringBuilder();

                String line;
                while ((line = reader.readLine()) != null) {
                    file.append(line).append('\n');
                }

                reader.close();

                // Write the file string to a book
                writeBook(file.toString().chars().iterator(), false);
            } catch (IOException ignored) {
                error("Failed to read the file.");
            }
        }
    }

    private void writeBook(PrimitiveIterator.OfInt chars, boolean fillPages) {
        ArrayList<String> pages = new ArrayList<>();
        TextHandler.WidthRetriever widthRetriever = ((TextHandlerAccessor) mc.textRenderer.getTextHandler()).getWidthRetriever();

        int maxPages = mode.get() == Mode.File ? 100 : this.pages.get();

        int pageIndex = 0;
        int lineIndex = 0;

        final StringBuilder page = new StringBuilder();

        int totalBytes = 0;
        float lineWidth = 0;

        while (chars.hasNext()) {
            int c = chars.nextInt();

            float charWidth = widthRetriever.getWidth(c, Style.EMPTY);
            if (charWidth > this.maxCharWidth.get()) continue;

            int charBytes = new String(Character.toChars(c)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

            if (c == '\r' || c == '\n') {
                page.append('\n');
                lineIndex++;
                totalBytes += 1;
                lineWidth = 0;
            } else {
                if ((totalBytes + charBytes) > this.bytes.get()) {
                    if (fillPages) {
                        continue; // Try again for a smaller character
                    }
                    lineIndex = this.lines.get(); // Otherwise, signal that this page is done
                } else {
                    if (lineWidth + charWidth > 114f) { // Reached end of line
                        page.append('\n');
                        lineIndex++;
                        totalBytes += 1;
                        lineWidth = 0;
                        // Wrap to next line, unless wrapping to next page
                        if (lineIndex != this.lines.get()) {
                            totalBytes += charBytes;
                            lineWidth += charWidth;
                            page.appendCodePoint(c);
                        }
                    } else if (lineWidth == 0f && c == ' ') {
                        continue; // Prevent leading space from text wrapping
                    } else {
                        totalBytes += charBytes;
                        lineWidth += charWidth;
                        page.appendCodePoint(c);
                    }
                }
            }

            // Reached end of page
            if (lineIndex == this.lines.get() || totalBytes >= this.bytes.get()) {
                MeteorClient.LOG.warn("Wrote " + totalBytes + " bytes in page " + pageIndex);
                pages.add(page.toString());
                page.setLength(0);
                pageIndex++;
                lineIndex = 0;
                totalBytes = 0;
                lineWidth = 0;

                // No more pages
                if (pageIndex == maxPages) break;

                // Wrap to next page
                if (c != '\r' && c != '\n') {
                    totalBytes += charBytes;
                    lineWidth += charWidth;
                    page.appendCodePoint(c);
                }
            }
        }

        // No more characters, end current page
        if (!page.isEmpty() && pageIndex != maxPages) {
            pages.add(page.toString());
        }

        // Get the title with count
        String title = name.get();
        if (count.get() && bookCount != 0) title += " #" + bookCount;

        // Write data to book
        mc.player.getMainHandStack().setSubNbt("title", NbtString.of(title));
        mc.player.getMainHandStack().setSubNbt("author", NbtString.of(mc.player.getGameProfile().getName()));

        // Write pages NBT
        NbtList pageNbt = new NbtList();
        pages.stream().map(NbtString::of).forEach(pageNbt::add);
        if (!pages.isEmpty()) mc.player.getMainHandStack().setSubNbt("pages", pageNbt);

        // Send book update to server
        mc.player.networkHandler.sendPacket(new BookUpdateC2SPacket(mc.player.getInventory().selectedSlot, pages, sign.get() ? Optional.of(title) : Optional.empty()));

        bookCount++;
    }

    private void writeRandomBook() {
        PrimitiveIterator.OfInt threebytechars = random.ints(0x0800, 0x10000)
            .filter(i -> !Character.isWhitespace(i))
            .iterator();

        PrimitiveIterator.OfInt onebytechars = random.ints(0x21, 0x7E)
            .filter(i -> !Character.isWhitespace(i) && i != '\r' && i != '\n')
            .iterator();

        ArrayList<String> pages = new ArrayList<>();

        for (int pageI = 0; pageI < (mode.get() == Mode.File ? 100 : this.pages.get()); pageI++) {
            StringBuilder page = new StringBuilder();

            int totalBytes = 0;
            for (int lineI = 0; lineI < this.lines.get(); lineI++) {
                if (totalBytes >= this.bytes.get()) break;

                double lineWidth = 0;
                StringBuilder line = new StringBuilder();

                while (totalBytes + 3 <= this.bytes.get()) {
                    // Get the next character
                    int nextChar = threebytechars.nextInt();

                    // Ignore newline chars when writing lines, should already be organised
                    if (nextChar == '\r' || nextChar == '\n') break;

                    // Make sure the character will fit on the line
                    double charWidth = ((TextHandlerAccessor) mc.textRenderer.getTextHandler()).getWidthRetriever().getWidth(nextChar, Style.EMPTY);
                    if (charWidth > this.maxCharWidth.get()) continue;
                    if (lineWidth + charWidth > 114) break;

                    int charBytes = new String(Character.toChars(nextChar)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    if ((totalBytes + charBytes) > this.bytes.get()) continue;

                    // Append it to the line
                    line.appendCodePoint(nextChar);
                    lineWidth += charWidth;
                    totalBytes += charBytes;
                }

                if (totalBytes + 3 > this.bytes.get()) {
                    if (totalBytes < this.bytes.get()) {
                        MeteorClient.LOG.warn("Page almost full! Wrote " + totalBytes + " bytes of 3-byte characters");
                    } else {
                        MeteorClient.LOG.warn("Page already full! Wrote " + totalBytes + " bytes of 3-byte characters");
                    }
                    while(totalBytes < this.bytes.get()) {
                        if (lineWidth + 2 > 114) {
                            MeteorClient.LOG.warn("Didn't write extra byte, line too long");
                            break;
                        }

                        int nextChar = onebytechars.nextInt();

                        double charWidth = ((TextHandlerAccessor) mc.textRenderer.getTextHandler()).getWidthRetriever().getWidth(nextChar, Style.EMPTY);
                        if (lineWidth + charWidth > 114) continue;

                        line.appendCodePoint(nextChar);
                        lineWidth += charWidth;
                        totalBytes += 1;
                        MeteorClient.LOG.warn("Wrote extra byte, page is now " + totalBytes + " bytes");
                    }
                }

                // Append the line to the page
                page.append(line);
                if (totalBytes < this.bytes.get()) {
                    page.append('\n');
                    totalBytes += 1;
                    if (totalBytes == this.bytes.get()) {
                        MeteorClient.LOG.warn("Wrote final newline, page is now " + totalBytes + " bytes");
                    }
                }
            }

            // Append page to the page list
            pages.add(page.toString());
        }

        // Get the title with count
        String title = name.get();
        if (count.get() && bookCount != 0) title += " #" + bookCount;

        // Write data to book
        mc.player.getMainHandStack().setSubNbt("title", NbtString.of(title));
        mc.player.getMainHandStack().setSubNbt("author", NbtString.of(mc.player.getGameProfile().getName()));

        // Write pages NBT
        NbtList pageNbt = new NbtList();
        pages.stream().map(NbtString::of).forEach(pageNbt::add);
        if (!pages.isEmpty()) mc.player.getMainHandStack().setSubNbt("pages", pageNbt);

        // Send book update to server
        mc.player.networkHandler.sendPacket(new BookUpdateC2SPacket(mc.player.getInventory().selectedSlot, pages, sign.get() ? Optional.of(title) : Optional.empty()));

        bookCount++;
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = super.toTag();

        if (file != null && file.exists()) {
            tag.putString("file", file.getAbsolutePath());
        }

        return tag;
    }

    @Override
    public Module fromTag(NbtCompound tag) {
        if (tag.contains("file")) {
            file = new File(tag.getString("file"));
        }

        return super.fromTag(tag);
    }

    public enum Mode {
        File,
        Random
    }
}
