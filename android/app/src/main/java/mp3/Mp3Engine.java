package mp3;

import mpg.Common;
import mpg.Interface;
import mpg.MPGLib;

/**
 * Small wrapper around the pure-Java LAME port (LGPL, from the lamejs/jump3r project).
 * Constant bitrate, no ID3, no Xing/Info frame, so every output file is a clean,
 * standalone MP3 whose size is predictable: ~ seconds * kbps * 1000 / 8 bytes.
 */
public final class Mp3Engine {
    private final Lame lame;
    private final LameGlobalFlags gfp;
    private final int channels;
    public final int outSampleRate;
    public final int bitrate;

    public Mp3Engine(int channelsIn, int inSampleRate, int outSampleRate, int kbps, boolean monoOut, int quality) {
        lame = new Lame();
        GetAudio gaud = new GetAudio();
        GainAnalysis ga = new GainAnalysis();
        BitStream bs = new BitStream();
        Presets p = new Presets();
        QuantizePVT qupvt = new QuantizePVT();
        Quantize qu = new Quantize();
        VBRTag vbr = new VBRTag();
        Version ver = new Version();
        ID3Tag id3 = new ID3Tag();
        Reservoir rv = new Reservoir();
        Takehiro tak = new Takehiro();
        Parse parse = new Parse();
        MPGLib mpg = new MPGLib();
        Interface intf = new Interface();
        Common common = new Common();

        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg);
        bs.setModules(ga, mpg, ver, vbr);
        id3.setModules(bs, ver);
        p.setModules(lame);
        qu.setModules(bs, rv, qupvt, tak);
        qupvt.setModules(tak, rv, lame.enc.psy);
        rv.setModules(bs);
        tak.setModules(qupvt);
        vbr.setModules(lame, bs, ver);
        gaud.setModules(parse, mpg);
        parse.setModules(ver, id3, p);
        mpg.setModules(intf, common);
        intf.setModules(vbr, common);

        channels = channelsIn;
        gfp = lame.lame_init();
        gfp.num_channels = channelsIn;
        gfp.in_samplerate = inSampleRate;
        gfp.out_samplerate = outSampleRate;
        gfp.mode = monoOut ? MPEGMode.MONO : MPEGMode.JOINT_STEREO;
        gfp.VBR = VbrMode.vbr_off;
        gfp.brate = kbps;
        gfp.quality = quality;
        id3.id3tag_init(gfp);
        gfp.write_id3tag_automatic = false;
        gfp.findReplayGain = false;
        gfp.bWriteVbrTag = false; // no Info frame: we never seek back to fill it in
        int rc = lame.lame_init_params(gfp);
        if (rc < 0) throw new IllegalArgumentException("LAME rejected settings (" + rc + ")");
        this.outSampleRate = gfp.out_samplerate;
        this.bitrate = gfp.brate;
    }

    /** Worst-case output buffer size for n input samples per channel. */
    public static int outBufferSize(int n) {
        return (int) (1.25 * n) + 7200;
    }

    /**
     * Encode n samples per channel. Values are 16-bit range (-32768..32767) stored in ints.
     * For mono input pass the same array (or null) as right.
     */
    public int encode(int[] left, int[] right, int n, byte[] out) {
        int[] r = (channels > 1 && right != null) ? right : left;
        int res = lame.lame_encode_buffer_int(gfp, left, r, n, out, 0, out.length);
        if (res < 0) throw new IllegalStateException("LAME encode error " + res);
        return res;
    }

    public int flush(byte[] out) {
        int res = lame.lame_encode_flush(gfp, out, 0, out.length);
        if (res < 0) throw new IllegalStateException("LAME flush error " + res);
        return res;
    }

    public void close() {
        lame.lame_close(gfp);
    }
}
