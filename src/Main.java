import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Assignment 1: Myers' O(ND) diff.
 *
 *   java Main lines     A B   -> Part A: minimal line diff
 *   java Main highlight A B   -> Part B: line diff + changed-character ranges
 */
public class Main {

    // Kinds of edit-script operations.
    static final int KEEP = 0, DELETE = 1, INSERT = 2;

    /** An edit script: op i has kind type[i] and refers to element idx[i]
     *  (index into A for KEEP/DELETE, index into B for INSERT). */
    static final class Script {
        int[] type;
        int[] idx;
    }

    // ------------------------------------------------------------------
    // 1. Myers' algorithm on any int sequence (lines or characters)
    // ------------------------------------------------------------------
    static Script myers(int[] a, int[] b) {
        final int n = a.length, m = b.length;
        final int max = n + m;
        final int off = max + 1;                 // v[off + k] holds the value for diagonal k
        final int[] v = new int[2 * max + 3];    // v[k] = furthest x reached on diagonal k
        final ArrayList<int[]> trace = new ArrayList<>();

        int dFinal = -1;
        outer:
        for (int d = 0; d <= max; d++) {
            // Save the part of V that this round can read (k = -d .. d).
            trace.add(Arrays.copyOfRange(v, off - d, off + d + 1));

            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) {
                    x = v[off + k + 1];          // come from diagonal k+1: move down (insert)
                } else {
                    x = v[off + k - 1] + 1;      // come from diagonal k-1: move right (delete)
                }
                int y = x - k;
                while (x < n && y < m && a[x] == b[y]) { x++; y++; }   // follow the snake
                v[off + k] = x;
                if (x >= n && y >= m) { dFinal = d; break outer; }
            }
        }

        // Backtrack from (n, m) to (0, 0), filling the script from the end.
        final int cnt = (n + m + dFinal) / 2;     // keeps + edits
        final Script s = new Script();
        s.type = new int[cnt];
        s.idx = new int[cnt];
        int pos = cnt - 1;
        int x = n, y = m;
        for (int d = dFinal; d > 0; d--) {
            final int[] prev = trace.get(d);      // V as it was after round d-1; index = k + d
            final int k = x - y;
            final int prevK;
            if (k == -d || (k != d && prev[k - 1 + d] < prev[k + 1 + d])) prevK = k + 1;
            else prevK = k - 1;
            final int prevX = prev[prevK + d];
            final int prevY = prevX - prevK;

            while (x > prevX && y > prevY) {      // the snake: lines kept
                x--; y--;
                s.type[pos] = KEEP; s.idx[pos] = x; pos--;
            }
            if (x == prevX) {                     // moved down: insertion of b[y-1]
                y--;
                s.type[pos] = INSERT; s.idx[pos] = y; pos--;
            } else {                              // moved right: deletion of a[x-1]
                x--;
                s.type[pos] = DELETE; s.idx[pos] = x; pos--;
            }
        }
        while (x > 0 && y > 0) {                  // the first snake (round d = 0)
            x--; y--;
            s.type[pos] = KEEP; s.idx[pos] = x; pos--;
        }
        return s;
    }

    // ------------------------------------------------------------------
    // 2. Reading a file into lines (as raw bytes)
    // ------------------------------------------------------------------
    /** Line i of the file is data[starts[i] .. starts[i] + lens[i]). */
    static final class Lines {
        byte[] data;
        int[] starts;
        int[] lens;
        int count;
    }

    static Lines split(byte[] data) {
        int cnt = 0;
        for (byte c : data) if (c == '\n') cnt++;
        if (data.length > 0 && data[data.length - 1] != '\n') cnt++;

        Lines r = new Lines();
        r.data = data;
        r.count = cnt;
        r.starts = new int[cnt];
        r.lens = new int[cnt];
        int s = 0, li = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n') {
                r.starts[li] = s;
                r.lens[li] = i - s;
                li++;
                s = i + 1;
            }
        }
        if (s < data.length) {
            r.starts[li] = s;
            r.lens[li] = data.length - s;
        }
        return r;
    }

    /** Give every distinct line an integer id, so comparing lines is comparing ints. */
    static int[] toIds(Lines l, HashMap<String, Integer> ids) {
        int[] r = new int[l.count];
        for (int i = 0; i < l.count; i++) {
            // ISO_8859_1 maps each byte to one char, so this is lossless for any bytes.
            String key = new String(l.data, l.starts[i], l.lens[i], StandardCharsets.ISO_8859_1);
            Integer id = ids.get(key);
            if (id == null) {
                id = ids.size();
                ids.put(key, id);
            }
            r[i] = id;
        }
        return r;
    }

    // ------------------------------------------------------------------
    // 3. Part B: changed-character ranges for one (old line, new line) pair
    // ------------------------------------------------------------------
    static String highlight(Lines la, int i, Lines lb, int j) {
        int[] oc = new String(la.data, la.starts[i], la.lens[i], StandardCharsets.UTF_8)
                .codePoints().toArray();
        int[] nc = new String(lb.data, lb.starts[j], lb.lens[j], StandardCharsets.UTF_8)
                .codePoints().toArray();
        Script s = myers(oc, nc);
        boolean[] oldMark = new boolean[oc.length];
        boolean[] newMark = new boolean[nc.length];
        for (int t = 0; t < s.type.length; t++) {
            if (s.type[t] == DELETE) oldMark[s.idx[t]] = true;
            else if (s.type[t] == INSERT) newMark[s.idx[t]] = true;
        }
        return "? " + ranges(oldMark) + " | " + ranges(newMark);
    }

    /** Turn marks into "3-5,9-12"; touching marks merge automatically. "." if none. */
    static String ranges(boolean[] mark) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < mark.length) {
            if (!mark[i]) { i++; continue; }
            int j = i;
            while (j < mark.length && mark[j]) j++;
            if (sb.length() > 0) sb.append(',');
            sb.append(i).append('-').append(j);
            i = j;
        }
        return sb.length() == 0 ? "." : sb.toString();
    }

    // ------------------------------------------------------------------
    // 4. Output
    // ------------------------------------------------------------------
    static void writeLine(OutputStream out, char prefix, Lines l, int i) throws IOException {
        out.write(prefix);
        out.write(l.data, l.starts[i], l.lens[i]);
        out.write('\n');
    }

    static void print(OutputStream out, Script s, Lines la, Lines lb, boolean highlight)
            throws IOException {
        int i = 0;
        final int n = s.type.length;
        while (i < n) {
            if (s.type[i] == KEEP) {
                writeLine(out, ' ', la, s.idx[i]);
                i++;
                continue;
            }
            // A change block: consecutive DELETE/INSERT ops with no KEEP between them.
            int j = i;
            int dels = 0, inss = 0;
            while (j < n && s.type[j] != KEEP) {
                if (s.type[j] == DELETE) dels++; else inss++;
                j++;
            }
            int[] delIdx = new int[dels];
            int[] insIdx = new int[inss];
            int di = 0, ii = 0;
            for (int t = i; t < j; t++) {
                if (s.type[t] == DELETE) delIdx[di++] = s.idx[t];
                else insIdx[ii++] = s.idx[t];
            }
            // Delete-first rule: all '-' lines, then all '+' lines.
            for (int t = 0; t < dels; t++) writeLine(out, '-', la, delIdx[t]);
            for (int t = 0; t < inss; t++) {
                writeLine(out, '+', lb, insIdx[t]);
                if (highlight && t < dels) {      // the t-th '+' pairs with the t-th '-'
                    out.write(highlight(la, delIdx[t], lb, insIdx[t])
                            .getBytes(StandardCharsets.US_ASCII));
                    out.write('\n');
                }
            }
            i = j;
        }
    }

    // ------------------------------------------------------------------
    // 5. main
    // ------------------------------------------------------------------
    public static void main(String[] args) throws IOException {
        if (args.length != 3 || !(args[0].equals("lines") || args[0].equals("highlight"))) {
            System.err.println("usage: Main lines|highlight <fileA> <fileB>");
            System.exit(1);
            return;
        }
        final boolean hl = args[0].equals("highlight");

        byte[] da, db;
        try {
            da = Files.readAllBytes(Path.of(args[1]));
            db = Files.readAllBytes(Path.of(args[2]));
        } catch (IOException | RuntimeException e) {
            System.err.println("error: cannot read input file: " + e);
            System.exit(2);
            return;
        }

        Lines la = split(da);
        Lines lb = split(db);
        HashMap<String, Integer> ids = new HashMap<>();
        int[] a = toIds(la, ids);
        int[] b = toIds(lb, ids);

        Script script = myers(a, b);

        OutputStream out = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16);
        print(out, script, la, lb, hl);
        out.flush();
    }
}
