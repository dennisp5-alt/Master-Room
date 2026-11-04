package com.dennis.masterroom.engine;

/** Analog-style colour: transformer, tape and valve behaviour at a calibrated level. */
final class Tone implements Oversample.Stage {
    /** Fast tanh by a rational approximation, held at +-1 beyond its useful range. */
    static double tanh(double x) {
        if (x > 4.9) return 1; if (x < -4.9) return -1;
        double x2 = x * x;
        double a = x * (135135 + x2 * (17325 + x2 * (378 + x2)));
        double b = 135135 + x2 * (62370 + x2 * (3150 + x2 * 28));
        double y = a / b;
        return y > 1 ? 1 : y < -1 ? -1 : y;
    }

    private final double op, valve, bias, biasOut, norm, iron, tape;
    private final double aIron, aTape, aDc;
    private double loL, loR, hfL, hfR, dcL, dcR;

    /**
     * @param operatingDb gain that brings the song to the path's nominal level
     * @param valveDrive 0 off; about 1 is a driven valve stage
     * @param ironAmount 0..1 transformer low-end saturation
     * @param tapeAmount 0..1 tape-like softening of loud high frequencies
     */
    Tone(int rate, double operatingDb, double valveDrive, double ironAmount, double tapeAmount) {
        op = Math.pow(10, operatingDb / 20);
        valve = Math.max(1e-6, valveDrive);
        bias = 0.06;
        biasOut = tanh(valve * bias);
        double s = 1 - biasOut * biasOut;
        norm = 1 / (valve * s);
        iron = ironAmount; tape = tapeAmount;
        aIron = 1 - Math.exp(-2 * Math.PI * 140.0 / rate);
        aTape = 1 - Math.exp(-2 * Math.PI * 5000.0 / rate);
        aDc = 1 - Math.exp(-2 * Math.PI * 6.0 / rate);
    }

    @Override public int latency() { return 0; }

    @Override public void process(double[] l, double[] r, int n) {
        final double irOp = 1.6, tpOp = 3.2;
        for (int i = 0; i < n; i++) {
            double x = l[i] * op, y = r[i] * op;
            // Transformer: the core saturates on flux, so lows go first.
            loL += (x - loL) * aIron; loR += (y - loR) * aIron;
            if (iron > 0) {
                x += iron * (tanh(loL * irOp) / irOp - loL);
                y += iron * (tanh(loR * irOp) / irOp - loR);
            }
            // Tape: loud high frequencies are softened, quiet detail is untouched.
            hfL += (x - hfL) * aTape; hfR += (y - hfR) * aTape;
            if (tape > 0) {
                double hl = x - hfL, hr = y - hfR;
                x += tape * (tanh(hl * tpOp) / tpOp - hl);
                y += tape * (tanh(hr * tpOp) / tpOp - hr);
            }
            // Valve: slight asymmetry gives second harmonic, the curve gives third.
            if (valve > 1e-5) {
                x = (tanh(valve * (x + bias)) - biasOut) * norm;
                y = (tanh(valve * (y + bias)) - biasOut) * norm;
            }
            dcL += (x - dcL) * aDc; dcR += (y - dcR) * aDc;
            l[i] = (x - dcL) / op; r[i] = (y - dcR) / op;
        }
    }
}
