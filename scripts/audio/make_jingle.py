# Jingle «Fine scenario» di SimilFranz — composto e sintetizzato qui, nessun campione esterno: CC0.
# Idea: due battiti del cuore (lub-dub) che si sciolgono in un arpeggio maggiore ascendente e in un accordo
# sospeso che si risolve: «il cuore riparte, missione compiuta».
import math, wave, struct, random, sys
SR = 44100; DUR = 5.2
N = int(SR * DUR); buf = [0.0] * N
def f(n): return 440.0 * 2 ** ((n - 69) / 12)
def add(t0, gen, dur):
    s = int(t0 * SR)
    for i in range(int(dur * SR)):
        if s + i < N: buf[s + i] += gen(i / SR)
def thump(freq, amp):          # battito: sinusoide grave con glissando verso il basso
    def g(t):
        fr = freq * (1 + 0.6 * math.exp(-t * 30))
        return amp * math.sin(2 * math.pi * fr * t) * math.exp(-t * 14) * min(1, t * 400)
    return g
def bell(n, amp, decay=2.2):    # marimba/campana morbida: parziali armoniche e inarmoniche che decadono
    fr = f(n)
    parts = [(1, 1.0, 1.0), (2, 0.35, 1.8), (3, 0.12, 2.6), (4.2, 0.06, 4.0)]
    def g(t):
        a = min(1, t * 250)
        return amp * a * sum(w * math.sin(2 * math.pi * fr * m * t) * math.exp(-t * decay * d) for m, w, d in parts)
    return g
def pad(notes, amp, dur):       # tappeto caldo: corde leggermente stonate, attacco lento
    def g(t):
        env = min(1, t / 0.6) * (1 if t < dur - 1.2 else max(0, (dur - t) / 1.2))
        v = 0
        for n in notes:
            for det in (-0.12, 0.0, 0.12):
                v += math.sin(2 * math.pi * f(n + det) * t + det)
        return amp * env * v / (len(notes) * 3)
    return g
# 1) due battiti (lub-dub), il secondo un po' più vicino: il cuore che riprende
for t0 in (0.00, 0.62):
    add(t0, thump(62, 0.55), 0.35); add(t0 + 0.17, thump(55, 0.40), 0.35)
# 2) arpeggio ascendente in Sol maggiore: Re4 Sol4 Si4 Re5 Sol5
for k, n in enumerate([62, 67, 71, 74, 79]):
    add(1.30 + k * 0.16, bell(n, 0.30), 2.5)
# 3) accordo finale: sospeso (Do) che si risolve sul Sol maggiore, con il tappeto sotto
add(2.20, pad([55, 60, 67, 72], 0.22, 0.9), 0.9)             # Sol sus4
add(2.95, pad([55, 59, 67, 71, 74], 0.26, 2.25), 2.25)       # Sol maggiore
for n, d in ((79, 0.0), (83, 0.05), (86, 0.10)):
    add(2.95 + d, bell(n, 0.16, 1.1), 2.2)
add(2.95, thump(49, 0.30), 0.5)                               # basso morbido sulla risoluzione
# riverbero semplice (eco diffusa) e normalizzazione
out = buf[:]
for dl, g in ((0.031, 0.32), (0.047, 0.27), (0.071, 0.22), (0.113, 0.17), (0.161, 0.12)):
    d = int(dl * SR)
    for i in range(d, N): out[i] += g * out[i - d] * 0.6
fade = int(0.4 * SR)
for i in range(fade): out[N - 1 - i] *= i / fade
pk = max(abs(x) for x in out); out = [x / pk * 0.85 for x in out]
w = wave.open(sys.argv[1], 'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
w.writeframes(b''.join(struct.pack('<h', int(x * 32767)) for x in out)); w.close()
