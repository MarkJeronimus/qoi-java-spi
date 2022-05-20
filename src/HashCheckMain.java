import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @author Zom-B
 */
// Created 2022-05-19
public class HashCheckMain {
	private static final int[] COLORS =
			{0x00, 0x10, 0x20, 0x30, 0x40, 0x50, 0x55, 0x60, 0x70, 0x80, 0x90, 0xA0, 0xAA, 0xB0, 0xC0, 0xD0, 0xE0,
			 0xF0, 0xFF};
	private static final int[] ALPHAS =
			{0x00, 0x55, 0x80, 0xAA, 0xFF};

	public static void main(String... args) {
		int[] coefs = {3, 5, 7, 11};

		float bestScore   = 0.0f;
		float bestScoreAt = 256;
		while (bestScore < 1.0f) {
			int   coefsSum = coefs[0] + coefs[1] + coefs[2] + coefs[3];
			float score    = test(coefs, false);
			if (bestScore < score || (bestScore == score && bestScoreAt >= coefsSum)) {
				bestScore = score;
				bestScoreAt = coefsSum;
				test(coefs, true);
			}

			int index = ThreadLocalRandom.current().nextInt(4);
			coefs[index] = ThreadLocalRandom.current().nextInt(255) + 1;
		}
	}

	private static float test(int[] coefs, boolean log) {
		int[] hashHits = new int[63];
		for (int a : ALPHAS) {
			int suma = (byte)a * coefs[3];
			for (int b : COLORS) {
				int sumba = (byte)b * coefs[2] + suma;
				for (int g : COLORS) {
					int sumgba = (byte)g * coefs[1] + sumba;
					for (int r : COLORS) {
						int hash = Math.floorMod((byte)r * coefs[0] + sumgba, hashHits.length);
						hashHits[hash]++;
					}
				}
			}
		}

		Arrays.sort(hashHits);
		float score = hashHits[0] / (float)hashHits[hashHits.length - 1];

		if (log)
			System.out.printf("%.5f %s %s\n", score, Arrays.toString(coefs), Arrays.toString(hashHits));

		return score;
	}
}
