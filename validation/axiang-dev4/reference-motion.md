# Motion review for 1.1-dev.4

The user-provided Samsung reference was reviewed locally frame by frame. In the 30fps camera clip, the A key visibly compresses then expands around 5.333–5.467s; N does so around 5.667–5.833s. The letter popup is separate from the key face. The second camera clip retains a purple underglow after the hand moves away around 7.8–8.4s.

Finger occlusion, perspective and 30fps sampling prevent exact DOWN/UP timing or a reliable above-rest overshoot measurement. These observations support the compression/expansion sequence and independent light tail; they do not establish Samsung spring constants or precise amplitude.

Our own design uses an immediate 4% compression and up to 8% held compression, then one positive rebound up to 3% (bounded by the existing key margins). Ordinary short taps reach the rebound peak about 188–202ms after release and settle in about one second. Input commits remain independent of animation. Whole-cap colour stays on through compression and rebound, then fades during settling. The existing default 900ms soft-mist fade is preserved.

Only project-generated native rendering evidence is distributed here. The user's source videos and contact sheets are not republished.
