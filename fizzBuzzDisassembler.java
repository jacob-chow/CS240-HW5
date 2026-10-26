import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class fizzBuzzDisassembler {

    private static final int HEADER_SIZE = 24;

    private static class MhcFile {
        int textBase;
        int dataBase;
        int dataSize;

        ArrayList<Integer> instructions = new ArrayList<>();
        ArrayList<Byte> data = new ArrayList<>();
    }

    private static HashMap<Integer, String> labels =
            new HashMap<>();

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println(
                    "Usage: java MhcDisassembler fizzbuzz.mhc-bin");
            return;
        }

        try {
            MhcFile file = readFile(args[0]);
            createLabels(file);
            writeAssembly(file);

            System.out.println("Disassembly completed successfully.");
            System.out.println("Created disassembled.asm");
        } catch (Exception e) {
            System.out.println("Disassembly error: "
                    + e.getMessage());
        }
    }

    private static MhcFile readFile(String fileName)
            throws IOException {

        byte[] bytes = Files.readAllBytes(
                Paths.get(fileName));

        String header = new String(
                bytes,
                0,
                4,
                StandardCharsets.US_ASCII
        );

        if (!header.equals("MHC1")) {
            throw new IOException("Invalid MHC file.");
        }

        /*
         * A binary file has a zero byte after MHC1.
         * Otherwise, it is the ASCII format.
         */
        if (bytes.length > 4 && bytes[4] == 0) {
            return readBinary(bytes);
        }

        return readAscii(fileName);
    }

    private static MhcFile readBinary(byte[] bytes)
            throws IOException {

        MhcFile file = new MhcFile();

        DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(bytes));

        input.skipBytes(4);       // MHC1
        input.readInt();          // version

        file.textBase = input.readInt();
        int textBytes = input.readInt();

        file.dataBase = input.readInt();
        file.dataSize = input.readInt();

        for (int i = 0; i < textBytes / 4; i++) {
            file.instructions.add(input.readInt());
        }

        for (int i = 0; i < file.dataSize; i++) {
            file.data.add(input.readByte());
        }

        return file;
    }

    private static MhcFile readAscii(String fileName)
            throws IOException {

        MhcFile file = new MhcFile();

        List<String> lines = Files.readAllLines(
                Paths.get(fileName),
                StandardCharsets.US_ASCII
        );

        boolean inText = false;
        boolean inData = false;

        for (String line : lines) {
            line = line.trim();

            if (line.equals("[TEXT]")) {
                inText = true;
                inData = false;
                continue;
            }

            if (line.equals("[DATA]")) {
                inText = false;
                inData = true;
                continue;
            }

            if (line.startsWith("TEXT_BASE")) {
                file.textBase = parseHex(
                        line.split("\\s+")[1]);
                continue;
            }

            if (line.startsWith("DATA_BASE")) {
                file.dataBase = parseHex(
                        line.split("\\s+")[1]);
                continue;
            }

            if (line.startsWith("DATA_BYTES")) {
                file.dataSize = Integer.parseInt(
                        line.split("\\s+")[1]);
                continue;
            }

            if (inText) {
                String[] parts = line.split("\\s+");

                if (parts.length >= 2) {
                    file.instructions.add(
                            parseHex(parts[1]));
                }
            }

            if (inData) {
                String[] parts = line.split("\\s+");

                for (int i = 1; i < parts.length; i++) {
                    if (parts[i].matches("[0-9A-Fa-f]{2}")) {
                        file.data.add((byte) Integer.parseInt(
                                parts[i],
                                16
                        ));
                    } else {
                        break;
                    }
                }
            }
        }

        return file;
    }

    private static void createLabels(MhcFile file) {
        labels.put(file.textBase, "main");

        for (int i = 0; i < file.instructions.size(); i++) {
            int address = file.textBase + i * 4;
            int instruction = file.instructions.get(i);

            int opcode = (instruction >>> 26) & 0x3f;

            if (opcode == 4 || opcode == 5) {
                short offset = (short)
                        (instruction & 0xffff);

                int target = address + 4 + offset * 4;

                if (!labels.containsKey(target)) {
                    labels.put(
                            target,
                            "label_" + Integer.toHexString(target)
                    );
                }
            }

            if (opcode == 2) {
                int target = ((address + 4) & 0xf0000000)
                        | ((instruction & 0x03ffffff) << 2);

                if (!labels.containsKey(target)) {
                    labels.put(
                            target,
                            "label_" + Integer.toHexString(target)
                    );
                }
            }
        }
    }

    private static void writeAssembly(MhcFile file)
            throws IOException {

        try (PrintWriter output = new PrintWriter(
                Files.newBufferedWriter(
                        Paths.get("disassembled.asm"),
                        StandardCharsets.US_ASCII))) {

            output.println(".text");
            output.println(".globl main");
            output.println();

            for (int i = 0; i < file.instructions.size(); i++) {
                int address = file.textBase + i * 4;

                if (labels.containsKey(address)) {
                    output.println(labels.get(address) + ":");
                }

                String instruction = decode(
                        file.instructions.get(i),
                        address
                );

                output.println("    " + instruction);
            }

            output.println();
            output.println(".data");

            writeData(output, file);
        }
    }

    private static String decode(
            int instruction,
            int address) {

        int opcode = (instruction >>> 26) & 0x3f;

        if (instruction == 0x0000000c) {
            return "syscall";
        }

        if (opcode == 0) {
            int rs = (instruction >>> 21) & 0x1f;
            int rt = (instruction >>> 16) & 0x1f;
            int rd = (instruction >>> 11) & 0x1f;
            int funct = instruction & 0x3f;

            if (funct == 0x21) {
                return "addu " + register(rd) + ", "
                        + register(rs) + ", "
                        + register(rt);
            }

            if (funct == 0x2a) {
                return "slt " + register(rd) + ", "
                        + register(rs) + ", "
                        + register(rt);
            }

            if (funct == 0x1a) {
                return "div " + register(rs) + ", "
                        + register(rt);
            }

            if (funct == 0x10) {
                return "mfhi " + register(rd);
            }
        }

        if (opcode == 2) {
            int target = ((address + 4) & 0xf0000000)
                    | ((instruction & 0x03ffffff) << 2);

            return "j " + labels.get(target);
        }

        if (opcode == 4 || opcode == 5) {
            int rs = (instruction >>> 21) & 0x1f;
            int rt = (instruction >>> 16) & 0x1f;

            short offset = (short)
                    (instruction & 0xffff);

            int target = address + 4 + offset * 4;

            String operation =
                    opcode == 4 ? "beq" : "bne";

            return operation + " "
                    + register(rs) + ", "
                    + register(rt) + ", "
                    + labels.get(target);
        }

        int rs = (instruction >>> 21) & 0x1f;
        int rt = (instruction >>> 16) & 0x1f;
        int immediate = instruction & 0xffff;

        if (opcode == 8) {
            return "addi " + register(rt) + ", "
                    + register(rs) + ", "
                    + signedValue(immediate);
        }

        if (opcode == 9) {
            return "addiu " + register(rt) + ", "
                    + register(rs) + ", "
                    + signedValue(immediate);
        }

        if (opcode == 13) {
            return "ori " + register(rt) + ", "
                    + register(rs) + ", "
                    + immediate;
        }

        if (opcode == 15) {
            return "lui " + register(rt) + ", "
                    + immediate;
        }

        throw new IllegalArgumentException(
                "Unsupported instruction at address 0x"
                        + Integer.toHexString(address));
    }

    private static void writeData(
            PrintWriter output,
            MhcFile file) {

        int address = file.dataBase;
        int index = 0;
        int dataLabelNumber = 0;

        while (index < file.data.size()) {
            output.println(
                    "dataLabel" + dataLabelNumber + ":");

            int nullPosition = -1;

            for (int i = index; i < file.data.size(); i++) {
                if (file.data.get(i) == 0) {
                    nullPosition = i;
                    break;
                }
            }

            if (nullPosition != -1) {
                byte[] text = new byte[nullPosition - index];

                for (int i = 0; i < text.length; i++) {
                    text[i] = file.data.get(index + i);
                }

                output.println(
                        "    .asciiz \""
                                + escape(text)
                                + "\"");

                index = nullPosition + 1;
                address = file.dataBase + index;
            } else {
                byte[] text = new byte[file.data.size() - index];

                for (int i = 0; i < text.length; i++) {
                    text[i] = file.data.get(index + i);
                }

                output.println(
                        "    .ascii \""
                                + escape(text)
                                + "\"");

                index = file.data.size();
            }

            output.println();
            dataLabelNumber++;
        }
    }

    private static String register(int number) {
        String[] registers = {
                "$zero", "$at", "$v0", "$v1",
                "$a0", "$a1", "$a2", "$a3",
                "$t0", "$t1", "$t2", "$t3",
                "$t4", "$t5", "$t6", "$t7",
                "$s0", "$s1", "$s2", "$s3",
                "$s4", "$s5", "$s6", "$s7",
                "$t8", "$t9", "$k0", "$k1",
                "$gp", "$sp", "$fp", "$ra"
        };

        return registers[number];
    }

    private static String escape(byte[] bytes) {
        StringBuilder text = new StringBuilder();

        for (byte value : bytes) {
            int number = value & 0xff;

            if (number == '\n') {
                text.append("\\n");
            } else if (number == '\t') {
                text.append("\\t");
            } else if (number == '\r') {
                text.append("\\r");
            } else if (number == '"') {
                text.append("\\\"");
            } else if (number == '\\') {
                text.append("\\\\");
            } else {
                text.append((char) number);
            }
        }

        return text.toString();
    }

    private static int signedValue(int value) {
        if ((value & 0x8000) != 0) {
            return value - 65536;
        }

        return value;
    }

    private static int parseHex(String value) {
        value = value.trim();

        if (value.startsWith("0x")
                || value.startsWith("0X")) {
            return (int) Long.parseLong(
                    value.substring(2),
                    16
            );
        }

        return Integer.parseInt(value);
    }
}