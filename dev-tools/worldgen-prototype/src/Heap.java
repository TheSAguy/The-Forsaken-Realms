import java.util.Arrays;

/** Growable binary min-heap of (int cell, float key) without boxing. */
final class Heap {
    int[] idx; float[] key; int size;

    Heap(int cap) { idx = new int[Math.max(16, cap)]; key = new float[idx.length]; }

    void clear() { size = 0; }

    void push(int i, float k) {
        if (size == idx.length) { idx = Arrays.copyOf(idx, size * 2); key = Arrays.copyOf(key, size * 2); }
        int c = size++;
        while (c > 0) { int p = (c - 1) >> 1; if (key[p] <= k) break; idx[c] = idx[p]; key[c] = key[p]; c = p; }
        idx[c] = i; key[c] = k;
    }

    float topKey() { return key[0]; }

    int pop() {
        int top = idx[0], li = idx[--size]; float lk = key[size]; int c = 0;
        while (true) {
            int l = 2 * c + 1; if (l >= size) break;
            int r = l + 1, m = (r < size && key[r] < key[l]) ? r : l;
            if (key[m] >= lk) break;
            idx[c] = idx[m]; key[c] = key[m]; c = m;
        }
        idx[c] = li; key[c] = lk;
        return top;
    }
}
