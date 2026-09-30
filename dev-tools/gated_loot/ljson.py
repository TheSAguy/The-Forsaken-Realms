"""ljson.py - a lenient JSON reader in the spirit of libGDX's JsonReader (optional commas, trailing commas,
comments, raw newlines inside strings, bare words). Read-only helper for the dialog audit."""


class _P:
    def __init__(self, s):
        self.s = s
        self.i = 0

    def ws(self):
        s = self.s
        while self.i < len(s):
            c = s[self.i]
            if c in " \t\r\n﻿":
                self.i += 1
            elif s.startswith("//", self.i):
                j = s.find("\n", self.i)
                self.i = len(s) if j < 0 else j + 1
            elif s.startswith("/*", self.i):
                j = s.find("*/", self.i + 2)
                self.i = len(s) if j < 0 else j + 2
            else:
                break

    def value(self):
        self.ws()
        if self.i >= len(self.s):
            raise ValueError("eof")
        c = self.s[self.i]
        if c == "{":
            return self.obj()
        if c == "[":
            return self.arr()
        if c in "\"'":
            return self.string(c)
        return self.bare()

    def obj(self):
        self.i += 1
        out = {}
        while True:
            self.ws()
            if self.i >= len(self.s):
                raise ValueError("eof in object")
            c = self.s[self.i]
            if c == "}":
                self.i += 1
                return out
            if c == ",":
                self.i += 1
                continue
            key = self.string(c) if c in "\"'" else self.bare(key=True)
            self.ws()
            if self.i < len(self.s) and self.s[self.i] in ":=":
                self.i += 1
            out[str(key)] = self.value()

    def arr(self):
        self.i += 1
        out = []
        while True:
            self.ws()
            if self.i >= len(self.s):
                raise ValueError("eof in array")
            c = self.s[self.i]
            if c == "]":
                self.i += 1
                return out
            if c == ",":
                self.i += 1
                continue
            out.append(self.value())

    def string(self, q):
        self.i += 1
        buf = []
        s = self.s
        while self.i < len(s):
            c = s[self.i]
            if c == "\\":
                n = s[self.i + 1] if self.i + 1 < len(s) else ""
                self.i += 2
                if n == "u":
                    buf.append(chr(int(s[self.i:self.i + 4], 16)))
                    self.i += 4
                else:
                    buf.append({"n": "\n", "t": "\t", "r": "\r", "b": "\b", "f": "\f"}.get(n, n))
            elif c == q:
                self.i += 1
                return "".join(buf)
            else:
                buf.append(c)
                self.i += 1
        raise ValueError("eof in string")

    def bare(self, key=False):
        s = self.s
        j = self.i
        stop = ":,]}\r\n" if not key else ":,]}\r\n\t "
        while j < len(s) and s[j] not in stop and not s.startswith("//", j):
            j += 1
        tok = s[self.i:j].strip()
        self.i = j
        if key:
            return tok
        if tok == "true":
            return True
        if tok == "false":
            return False
        if tok == "null":
            return None
        try:
            return int(tok)
        except ValueError:
            try:
                return float(tok)
            except ValueError:
                return tok


def loads(s):
    if s is None:
        return None
    s = s.strip()
    if not s:
        return None
    p = _P(s)
    v = p.value()
    return v
