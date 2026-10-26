import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class fizzBuzzAssembler {
    public static final int TEXT_BASE = 0x00400000;
    public static final int DATA_BASE = 0x10010000;

    public static class Instruction {
        String op;
        List<String> args;
        String source;

        Instruction(String op, List<String> args, String source) {
            this.op = op.toLowerCase();
            this.args = args;
            this.source = source;
        }
    }

    public static class DataItem {
        int address;
        byte[] bytes;
        String source;

        DataItem(int address, byte[] bytes, String source) {
            this.address = address;
            this.bytes = bytes;
            this.source = source;
        }
    }

    public static Map<String, Integer> labels = new LinkedHashMap<>();
    public static List<Instruction> instructions = new ArrayList<>();
    public static List<DataItem> dataItems = new ArrayList<>();
    public static List<Integer> machineCode = new ArrayList<>();
    public static List<String> machineSource = new ArrayList<>();

    public static int dataSize = 0;

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage:");
            System.out.println("java FizzBuzzAssembler mips6(1).asm");
            return;
        }

        try {
            firstPass(Paths.get(args[0]));
            secondPass();

            writeAsciiFile(Paths.get("fizzbuzz.mhc-ascii"));
            writeBinaryFile(Paths.get("fizzbuzz.mhc-bin"));

            System.out.println("Assembly completed successfully.");
            System.out.println("Created fizzbuzz.mhc-ascii");
            System.out.println("Created fizzbuzz.mhc-bin");
        } catch (Exception e) {
            System.err.println("Assembly error: " + e.getMessage());
        }
    }

    public static void firstPass(Path file) throws IOException {
        boolean textSection = false;
        int textAddress = TEXT_BASE;
        int dataAddress = DATA_BASE;

        List<String> lines = Files.readAllLines(file);

        for (int lineNumber = 0; lineNumber < lines.size(); lineNumber++) {
            String original = lines.get(lineNumber).trim();
            String line = removeComment(original).trim();

            if (line.isEmpty()) {
                continue;
            }

            if (line.equalsIgnoreCase(".data")) {
                textSection = false;
                continue;
            }

            if (line.equalsIgnoreCase(".text")) {
                textSection = true;
                continue;
            }

            if (line.toLowerCase().startsWith(".globl")) {
                continue;
            }

            if (line.contains(":")) {
                int colon = line.indexOf(":");

                String labelName = line.substring(0, colon).trim();

                if (!labelName.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    assemblyError(lineNumber, "Invalid label: " + labelName);
                }

                if (labels.containsKey(labelName)) {
                    assemblyError(lineNumber, "Duplicate label: " + labelName);
                }

                if (textSection) {
                    labels.put(labelName, textAddress);
                } else {
                    labels.put(labelName, dataAddress);
                }

                line = line.substring(colon + 1).trim();

                if (line.isEmpty()) {
                    continue;
                }
            }

            if (!textSection) {
                String[] parts = line.split("\\s+", 2);

                if (parts.length != 2) {
                    assemblyError(lineNumber,
                            "Invalid data directive: " + line);
                }

                String directive = parts[0].toLowerCase();

                if (!directive.equals(".ascii")
                        && !directive.equals(".asciiz")) {
                    assemblyError(lineNumber,
                            "Unsupported data directive: " + directive);
                }

                byte[] bytes = decodeString(parts[1].trim());

                if (directive.equals(".asciiz")) {
                    bytes = Arrays.copyOf(bytes, bytes.length + 1);
                }

                dataItems.add(new DataItem(dataAddress, bytes, original));

                dataAddress += bytes.length;
                dataSize += bytes.length;
            } else {
                ParsedInstruction parsed = parseInstruction(line);

                int instructionCount =
                        expandedInstructionCount(parsed.op, parsed.args);

                instructions.add(new Instruction(
                        parsed.op,
                        parsed.args,
                        original
                ));

                textAddress += instructionCount * 4;
            }
        }
    }

    public static int expandedInstructionCount(
            String op,
            List<String> args) {

        switch (op.toLowerCase()) {
            case "li":
                return 1;

            case "move":
                return 1;

            case "la":
                return 2;

            case "bgt":
                if (isRegister(args.get(1))) {
                    return 2;
                } else {
                    return 3;
                }

            case "rem":
                if (isRegister(args.get(2))) {
                    return 2;
                } else {
                    return 3;
                }

            case "addi":
            case "addiu":
            case "addu":
            case "slt":
            case "beq":
            case "bne":
            case "j":
            case "lui":
            case "ori":
            case "div":
            case "mfhi":
            case "syscall":
                return 1;

            default:
                throw new IllegalArgumentException(
                        "Unsupported instruction: " + op);
        }
    }

    public static void secondPass() {
        for (Instruction instruction : instructions) {
            List<Instruction> expanded =
                    expandInstruction(instruction);

            for (Instruction item : expanded) {
                int binary = encodeInstruction(item);

                machineCode.add(binary);
                machineSource.add(instruction.source);
            }
        }
    }

    public static List<Instruction> expandInstruction(
            Instruction instruction) {

        String op = instruction.op;
        List<String> a = instruction.args;

        List<Instruction> result = new ArrayList<>();

        switch (op) {
            case "li":
                int value = parseNumber(a.get(1));

                if (value < -32768 || value > 32767) {
                    throw new IllegalArgumentException(
                            "li value is outside the supported range: "
                                    + value);
                }

                result.add(new Instruction(
                        "addiu",
                        Arrays.asList(a.get(0), "$zero",
                                Integer.toString(value)),
                        instruction.source
                ));
                break;

            case "move":
                result.add(new Instruction(
                        "addu",
                        Arrays.asList(a.get(0), a.get(1), "$zero"),
                        instruction.source
                ));
                break;

            case "la":
                int address = getLabelAddress(a.get(1));

                result.add(new Instruction(
                        "lui",
                        Arrays.asList(
                                a.get(0),
                                Integer.toString((address >>> 16) & 0xffff)
                        ),
                        instruction.source
                ));

                result.add(new Instruction(
                        "ori",
                        Arrays.asList(
                                a.get(0),
                                a.get(0),
                                Integer.toString(address & 0xffff)
                        ),
                        instruction.source
                ));
                break;

            case "bgt":
                if (!isRegister(a.get(1))) {
                    result.add(new Instruction(
                            "addiu",
                            Arrays.asList(
                                    "$at",
                                    "$zero",
                                    a.get(1)
                            ),
                            instruction.source
                    ));
                }

                String secondOperand =
                        isRegister(a.get(1)) ? a.get(1) : "$at";

                result.add(new Instruction(
                        "slt",
                        Arrays.asList("$at",
                                secondOperand,
                                a.get(0)),
                        instruction.source
                ));

                result.add(new Instruction(
                        "bne",
                        Arrays.asList("$at",
                                "$zero",
                                a.get(2)),
                        instruction.source
                ));
                break;

            case "rem":
                if (!isRegister(a.get(2))) {
                    result.add(new Instruction(
                            "addiu",
                            Arrays.asList(
                                    "$at",
                                    "$zero",
                                    a.get(2)
                            ),
                            instruction.source
                    ));

                    result.add(new Instruction(
                            "div",
                            Arrays.asList(a.get(1), "$at"),
                            instruction.source
                    ));
                } else {
                    result.add(new Instruction(
                            "div",
                            Arrays.asList(a.get(1), a.get(2)),
                            instruction.source
                    ));
                }

                result.add(new Instruction(
                        "mfhi",
                        Arrays.asList(a.get(0)),
                        instruction.source
                ));
                break;

            default:
                result.add(instruction);
        }

        return result;
    }

    public static int encodeInstruction(Instruction instruction) {
        String op = instruction.op;
        List<String> a = instruction.args;

        switch (op) {
            case "addi":
                return encodeIType(
                        8,
                        registerNumber(a.get(1)),
                        registerNumber(a.get(0)),
                        parseNumber(a.get(2))
                );

            case "addiu":
                return encodeIType(
                        9,
                        registerNumber(a.get(1)),
                        registerNumber(a.get(0)),
                        parseNumber(a.get(2))
                );

            case "ori":
                return encodeIType(
                        13,
                        registerNumber(a.get(1)),
                        registerNumber(a.get(0)),
                        parseNumber(a.get(2))
                );

            case "lui":
                return encodeIType(
                        15,
                        0,
                        registerNumber(a.get(0)),
                        parseNumber(a.get(1))
                );

            case "beq":
                return encodeBranch(4, a);

            case "bne":
                return encodeBranch(5, a);

            case "j":
                return (2 << 26)
                        | ((getLabelAddress(a.get(0)) >>> 2)
                        & 0x03ffffff);

            case "addu":
                return encodeRType(
                        registerNumber(a.get(1)),
                        registerNumber(a.get(2)),
                        registerNumber(a.get(0)),
                        0x21
                );

            case "slt":
                return encodeRType(
                        registerNumber(a.get(1)),
                        registerNumber(a.get(2)),
                        registerNumber(a.get(0)),
                        0x2a
                );

            case "div":
                return encodeRType(
                        registerNumber(a.get(0)),
                        registerNumber(a.get(1)),
                        0,
                        0x1a
                );

            case "mfhi":
                return encodeRType(
                        0,
                        0,
                        registerNumber(a.get(0)),
                        0x10
                );

            case "syscall":
                return 0x0000000c;

            default:
                throw new IllegalArgumentException(
                        "Cannot encode instruction: " + op);
        }
    }

    public static int encodeBranch(int opcode, List<String> args) {
        int instructionAddress =
                TEXT_BASE + machineCode.size() * 4;

        int targetAddress = getLabelAddress(args.get(2));

        int offset =
                (targetAddress - (instructionAddress + 4)) / 4;

        if (offset < -32768 || offset > 32767) {
            throw new IllegalArgumentException(
                    "Branch is out of range: " + args.get(2));
        }

        return encodeIType(
                opcode,
                registerNumber(args.get(0)),
                registerNumber(args.get(1)),
                offset
        );
    }

    public static int encodeIType(
            int opcode,
            int rs,
            int rt,
            int immediate) {

        if (immediate < -32768 || immediate > 65535) {
            throw new IllegalArgumentException(
                    "Immediate value is out of range: " + immediate);
        }

        return (opcode << 26)
                | (rs << 21)
                | (rt << 16)
                | (immediate & 0xffff);
    }

    public static int encodeRType(
            int rs,
            int rt,
            int rd,
            int funct) {

        return (rs << 21)
                | (rt << 16)
                | (rd << 11)
                | funct;
    }

    public static ParsedInstruction parseInstruction(String line) {
        String[] parts = line.trim().split("\\s+", 2);

        String op = parts[0].toLowerCase();

        List<String> args = new ArrayList<>();

        if (parts.length == 2) {
            String[] arguments = parts[1].split(",");

            for (String argument : arguments) {
                args.add(argument.trim());
            }
        }

        return new ParsedInstruction(op, args);
    }

    public static class ParsedInstruction {
        String op;
        List<String> args;

        ParsedInstruction(String op, List<String> args) {
            this.op = op;
            this.args = args;
        }
    }

    public static int registerNumber(String name) {
        Map<String, Integer> registers = new HashMap<>();

        registers.put("$zero", 0);
        registers.put("$at", 1);
        registers.put("$v0", 2);
        registers.put("$a0", 4);

        registers.put("$t0", 8);
        registers.put("$t1", 9);
        registers.put("$t2", 10);
        registers.put("$t3", 11);
        registers.put("$t4", 12);
        registers.put("$t5", 13);
        registers.put("$t6", 14);
        registers.put("$t7", 15);

        registers.put("$s0", 16);
        registers.put("$s1", 17);
        registers.put("$s2", 18);
        registers.put("$s3", 19);
        registers.put("$s4", 20);
        registers.put("$s5", 21);
        registers.put("$s6", 22);
        registers.put("$s7", 23);

        registers.put("$sp", 29);
        registers.put("$ra", 31);

        String register = name.toLowerCase();

        if (!registers.containsKey(register)) {
            throw new IllegalArgumentException(
                    "Unsupported register: " + name);
        }

        return registers.get(register);
    }

    public static int parseNumber(String value) {
        value = value.trim();

        if (value.equals("'\\n'")) {
            return 10;
        }

        if (value.startsWith("0x")
                || value.startsWith("0X")) {
            return Integer.parseInt(value.substring(2), 16);
        }

        if (value.length() == 3
                && value.charAt(0) == '\''
                && value.charAt(2) == '\'') {
            return value.charAt(1);
        }

        return Integer.parseInt(value);
    }

    public static boolean isRegister(String value) {
        return value.trim().startsWith("$");
    }

    public static int getLabelAddress(String label) {
        if (!labels.containsKey(label)) {
            throw new IllegalArgumentException(
                    "Undefined label: " + label);
        }

        return labels.get(label);
    }

    public static byte[] decodeString(String value) {
        if (value.length() < 2
                || value.charAt(0) != '"'
                || value.charAt(value.length() - 1) != '"') {
            throw new IllegalArgumentException(
                    "String must be surrounded by quotation marks: "
                            + value);
        }

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        for (int i = 1; i < value.length() - 1; i++) {
            char current = value.charAt(i);

            if (current == '\\') {
                i++;

                if (i >= value.length() - 1) {
                    throw new IllegalArgumentException(
                            "Unfinished escape sequence");
                }

                char escaped = value.charAt(i);

                if (escaped == 'n') {
                    output.write('\n');
                } else if (escaped == 't') {
                    output.write('\t');
                } else if (escaped == 'r') {
                    output.write('\r');
                } else if (escaped == '"' || escaped == '\\') {
                    output.write(escaped);
                } else {
                    throw new IllegalArgumentException(
                            "Unsupported escape sequence: \\" + escaped);
                }
            } else {
                output.write(current);
            }
        }

        return output.toByteArray();
    }

    public static String removeComment(String line) {
        boolean insideString = false;

        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);

            if (current == '"') {
                insideString = !insideString;
            }

            if (current == '#' && !insideString) {
                return line.substring(0, i);
            }
        }

        return line;
    }

    public static void writeAsciiFile(Path file) throws IOException {
        try (PrintWriter output = new PrintWriter(
                Files.newBufferedWriter(
                        file,
                        StandardCharsets.US_ASCII))) {

            output.println("MHC1");
            output.println("BYTE_ORDER BIG_ENDIAN");
            output.printf("TEXT_BASE 0x%08X%n", TEXT_BASE);
            output.printf("TEXT_BYTES %d%n", machineCode.size() * 4);
            output.printf("DATA_BASE 0x%08X%n", DATA_BASE);
            output.printf("DATA_BYTES %d%n", dataSize);

            output.println("[TEXT]");

            for (int i = 0; i < machineCode.size(); i++) {
                int address = TEXT_BASE + i * 4;

                output.printf(
                        "0x%08X 0x%08X  %s%n",
                        address,
                        machineCode.get(i),
                        machineSource.get(i)
                );
            }

            output.println("[DATA]");

            for (DataItem item : dataItems) {
                output.printf("0x%08X", item.address);

                for (byte b : item.bytes) {
                    output.printf(" %02X", b & 0xff);
                }

                output.println("  " + item.source);
            }
        }
    }

    public static void writeBinaryFile(Path file) throws IOException {
        try (DataOutputStream output =
                     new DataOutputStream(
                             Files.newOutputStream(file))) {

            output.writeBytes("MHC1");
            output.writeInt(1);
            output.writeInt(TEXT_BASE);
            output.writeInt(machineCode.size() * 4);
            output.writeInt(DATA_BASE);
            output.writeInt(dataSize);

            for (int word : machineCode) {
                output.writeInt(word);
            }

            for (DataItem item : dataItems) {
                output.write(item.bytes);
            }
        }
    }

    public static void assemblyError(int lineNumber, String message) {
        throw new IllegalArgumentException(
                "Line " + (lineNumber + 1) + ": " + message);
    }
}