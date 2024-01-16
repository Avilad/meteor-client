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

    private final Setting<Integer> maxBytes = sgGeneral.add(new IntSetting.Builder()
        .name("max-bytes")
        .description("The maximum number of bytes to write per page.")
        .defaultValue(1024)
        .range(0, 1280)
        .sliderRange(0, 1280)
        .visible(() -> mode.get() != Mode.File)
        .build()
    );

    private final Setting<Integer> maxChars = sgGeneral.add(new IntSetting.Builder()
        .name("max-chars")
        .description("The maximum number of characters to write per page.")
        .defaultValue(320)
        .range(0, 512)
        .sliderRange(0, 512)
        .visible(() -> mode.get() != Mode.File)
        .build()
    );

    private final Setting<Integer> maxCharWidth = sgGeneral.add(new IntSetting.Builder()
        .name("max-char-width")
        .description("The maximum acceptable width (in pixels) of randomly selected characters.")
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

    private final Setting<Boolean> excludeTwoByte = sgGeneral.add(new BoolSetting.Builder()
        .name("exclude-two-byte")
        .description("Excludes characters that are encoded as 2 bytes in UTF-8, i.e. U+0080 - U+07FF, unless needed to fill a page.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Random)
        .build()
    );

    private final Setting<Boolean> excludeOneByte = sgGeneral.add(new BoolSetting.Builder()
        .name("exclude-one-byte")
        .description("Excludes characters that are encoded as 1 byte in UTF-8, i.e. U+0000 - U+07F / ASCII, unless needed to fill a page.")
        .defaultValue(true)
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

    private final Setting<String> author = sgGeneral.add(new StringSetting.Builder()
        .name("author")
        .description("The author you want to give your books (defaults to player name).")
        .defaultValue("")
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

    private final Setting<Integer> countStart = sgGeneral.add(new IntSetting.Builder()
        .name("count-start")
        .description("What number to give the first book.")
        .defaultValue(1)
        .noSlider()
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
        bookCount = this.countStart.get();
    }

    @Override
    public void onDeactivate() {
        this.countStart.set(bookCount);
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
            int bound = onlyAscii.get() ? 0x7F : 0x10000;

            writeBook(
                // Generate a random load of ints to use as random characters
                random.ints(0, bound)
                    .filter(i -> {
                        byte charType = (byte)Character.getType(i);
                        return charType != Character.UNASSIGNED &&
                               charType != Character.NON_SPACING_MARK &&
                               charType != Character.ENCLOSING_MARK &&
                               charType != Character.COMBINING_SPACING_MARK &&
                               charType != Character.SPACE_SEPARATOR &&
                               charType != Character.LINE_SEPARATOR &&
                               charType != Character.PARAGRAPH_SEPARATOR &&
                               charType != Character.CONTROL &&
                               charType != Character.FORMAT &&
                               charType != Character.PRIVATE_USE &&
                               charType != Character.SURROGATE;
                    })
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


        boolean excludeTwoByte = this.excludeTwoByte.get();
        boolean excludeOneByte = this.excludeOneByte.get();

        int pageIndex = 0;
        int lineIndex = 0;

        final StringBuilder page = new StringBuilder();

        int totalBytes = 0;
        int totalChars = 0;
        float lineWidth = 0;

        while (chars.hasNext()) {
            int c = chars.nextInt();

            float charWidth = widthRetriever.getWidth(c, Style.EMPTY);
            if (charWidth > this.maxCharWidth.get()) continue;

            if (c < 0x80) {
                if (excludeOneByte && (totalBytes + 2) <= this.maxBytes.get()) {
                    continue;
                }
            } else if (c < 0x800) {
                if (excludeTwoByte && (totalBytes + 3) <= this.maxBytes.get()) {
                    continue;
                }
            }

            int charBytes = new String(Character.toChars(c)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

            if (c == '\r' || c == '\n') {
                if (lineIndex < (this.lines.get() - 1)) {
                    page.append('\n');
                    totalBytes += 1;
                    totalChars += 1;
                }
                lineIndex++;
                lineWidth = 0;
            } else {
                if ((totalBytes + charBytes) > this.maxBytes.get()) {
                    if (fillPages) {
                        continue; // Try again for a smaller character
                    }
                    MeteorClient.LOG.warn("Ran out of bytes, setting line to end");
                    lineIndex = this.lines.get(); // Otherwise, signal that this page is done
                } else {
                    if (lineWidth + charWidth > 114f) { // Reached end of line
                        lineIndex++;
                        lineWidth = 0;
                        if (lineIndex != this.lines.get()) {
//                            page.append('\n');
//                            totalBytes += 1;
//                            if ((totalBytes + charBytes) > this.bytes.get()) {
//                                if (fillPages && totalBytes < this.bytes.get()) {
//                                    continue; // Try again for a smaller character
//                                }
//                                MeteorClient.LOG.warn("Ran out of bytes after a newline, setting line to end");
//                                lineIndex = this.lines.get(); // Otherwise, signal that this page is done
//                            } else {
                                // Wrap to next line, unless wrapping to next page
                                totalBytes += charBytes;
                                totalChars += 1;
                                lineWidth += charWidth;
                                page.appendCodePoint(c);
//                            }
                        }
                    } else if (lineWidth == 0f && c == ' ') {
                        continue; // Prevent leading space from text wrapping
                    } else {
                        totalBytes += charBytes;
                        totalChars += 1;
                        lineWidth += charWidth;
                        page.appendCodePoint(c);
                    }
                }
            }

            // Reached end of page
            if (lineIndex == this.lines.get() || totalBytes >= this.maxBytes.get() || totalChars >= this.maxChars.get()) {
                if (lineIndex == this.lines.get()) {
                    MeteorClient.LOG.warn("Ran out of lines");
                } else if (totalBytes >= this.maxBytes.get()) {
                    MeteorClient.LOG.warn("Ran out of bytes");
                } else if (totalChars >= this.maxChars.get()) {
                    MeteorClient.LOG.warn("Ran out of characters");
                }
                MeteorClient.LOG.warn("Wrote " + totalBytes + " bytes in " + totalChars + " characters in page " + pageIndex);
                pages.add(page.toString());
                page.setLength(0);
                pageIndex++;
                lineIndex = 0;
                totalBytes = 0;
                totalChars = 0;
                lineWidth = 0;

                // No more pages
                if (pageIndex == maxPages) break;

                // Wrap to next page
                if (c != '\r' && c != '\n') {
                    totalBytes += charBytes;
                    totalChars += 1;
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
        if (count.get()) title += bookCount;

        // Write data to book
        mc.player.getMainHandStack().setSubNbt("title", NbtString.of(title));
        mc.player.getMainHandStack().setSubNbt("author", NbtString.of(this.author.get().isEmpty() ? mc.player.getGameProfile().getName() : this.author.get()));

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
