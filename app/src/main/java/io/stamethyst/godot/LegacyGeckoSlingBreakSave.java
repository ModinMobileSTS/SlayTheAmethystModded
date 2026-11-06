package io.stamethyst.godot;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Read-only support for the retired compatibility engine's private loopback origins. */
final class LegacyGeckoSlingBreakSave {
    private static final int LIMIT = LegacySlingBreakSaveService.MAX_LENGTH * 2;

    static List<String> read(File files) {
        List<String> saves = new ArrayList<>();
        File[] profiles = new File(files, "mozilla").listFiles(File::isDirectory);
        if (profiles == null) return saves;
        for (File profile : profiles) {
            File[] origins = new File(profile, "storage/default").listFiles(file ->
                file.isDirectory() && file.getName().matches("http\\+\\+\\+127\\.0\\.0\\.1\\+[0-9]{1,5}"));
            if (origins == null) continue;
            for (File origin : origins) {
                File source = new File(origin, "ls/data.sqlite");
                if (!source.isFile()) continue;
                try (SQLiteDatabase db = SQLiteDatabase.openDatabase(source.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                     Cursor row = db.rawQuery("SELECT utf16_length,conversion_type,compression_type,value FROM data WHERE key=? AND length(value)<=?",
                         new String[]{LegacySlingBreakSaveService.KEY, Integer.toString(LIMIT)})) {
                    if (row.moveToFirst()) {
                        String raw = decode(row.getBlob(3), row.getInt(0), row.getInt(1), row.getInt(2));
                        if (!saves.contains(raw)) saves.add(raw);
                    }
                } catch (Exception error) { Log.w("SlingBreakMigration", "Cannot read legacy Gecko save", error); }
            }
        }
        return saves;
    }

    /** Mozilla LSValue: conversion 0=UTF-16LE, 1=UTF-8; compression 1=raw Snappy. */
    static String decode(byte[] bytes, int characters, int conversion, int compression) throws IOException {
        if (bytes == null || bytes.length > LIMIT || characters < 0 || characters > LIMIT / 2 ||
            conversion < 0 || conversion > 1 || compression < 0 || compression > 1) throw new IOException("Unsupported Gecko value");
        byte[] plain = compression == 0 ? bytes : expand(bytes);
        Charset charset = conversion == 0 ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8;
        String text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plain)).toString();
        if (text.length() != characters) throw new IOException("Gecko character count mismatch");
        return text;
    }

    private static byte[] expand(byte[] encoded) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(encoded);
        long size = 0;
        for (int shift = 0; ; shift += 7) {
            if (shift > 28) throw new IOException("Invalid Snappy header");
            int b = octet(input); size |= (long) (b & 127) << shift;
            if ((b & 128) == 0) break;
        }
        if (size > LIMIT) throw new IOException("Snappy output exceeds limit");
        byte[] output = new byte[(int) size];
        int position = 0;
        while (input.available() > 0) {
            int tag = octet(input), kind = tag & 3;
            if (kind == 0) {
                long count = tag >>> 2;
                if (count >= 60) count = littleEndian(input, (int) count - 59);
                count++;
                if (count > output.length - position || count > input.available()) throw new IOException("Invalid Snappy literal");
                int length = (int) count;
                if (input.read(output, position, length) != length) throw new EOFException();
                position += length;
            } else {
                int count = kind == 1 ? 4 + ((tag >>> 2) & 7) : 1 + (tag >>> 2);
                long distance = kind == 1 ? ((tag & 224) << 3) | octet(input) : littleEndian(input, kind == 2 ? 2 : 4);
                if (distance <= 0 || distance > position || count > output.length - position) throw new IOException("Invalid Snappy copy");
                for (int i = 0; i < count; i++, position++) output[position] = output[position - (int) distance];
            }
        }
        if (position != output.length) throw new IOException("Snappy length mismatch");
        return output;
    }
    private static int octet(InputStream input) throws IOException {
        int b = input.read(); if (b < 0) throw new EOFException(); return b;
    }
    private static long littleEndian(InputStream input, int count) throws IOException {
        long n = 0; for (int i = 0; i < count; i++) n |= (long) octet(input) << (8 * i); return n;
    }
}
