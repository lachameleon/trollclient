package com.trollclient.dev;

import com.mojang.blaze3d.platform.NativeImage;
import com.trollclient.TrollClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Captures finished frames at a fixed frame rate for the showcase video.
 * Time only advances while recording (pauses skip loading screens), and when
 * the game renders slower than the target rate the last frame is repeated, so
 * the video always plays back at real speed. Frames are written as JPEGs on a
 * small thread pool so the game keeps its frame rate.
 */
public final class FrameRecorder {
	private static final int MAX_PENDING = 48;

	private static boolean active;
	private static boolean paused;
	private static Path dir;
	private static int fps;
	private static long recordedNanos;
	private static long lastNanos;
	private static int written = -1;
	private static ExecutorService pool;
	private static final AtomicInteger PENDING = new AtomicInteger();

	private FrameRecorder() {
	}

	public static boolean isActive() {
		return active && !paused;
	}

	public static void start(Path out, int framesPerSecond) {
		dir = out;
		fps = framesPerSecond;
		try {
			Files.createDirectories(dir);
			try (var old = Files.list(dir)) {
				for (Path p : old.toList()) {
					Files.deleteIfExists(p);
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		pool = Executors.newFixedThreadPool(4, r -> {
			Thread t = new Thread(r, "troll-frame-writer");
			t.setDaemon(true);
			return t;
		});
		recordedNanos = 0;
		written = -1;
		lastNanos = System.nanoTime();
		paused = false;
		active = true;
		TrollClient.LOGGER.info("[showcase] recording at {} fps into {}", fps, dir.toAbsolutePath());
	}

	public static void pause() {
		paused = true;
	}

	public static void resume() {
		lastNanos = System.nanoTime();
		paused = false;
	}

	/** Seconds of video recorded so far. */
	public static float seconds() {
		return recordedNanos / 1e9f;
	}

	public static int frames() {
		return written + 1;
	}

	public static void stop() {
		active = false;
		if (pool != null) {
			pool.shutdown();
			try {
				pool.awaitTermination(2, TimeUnit.MINUTES);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		TrollClient.LOGGER.info("[showcase] stopped after {} frames ({} s)", written + 1, String.format("%.1f", seconds()));
	}

	/** Called at the end of every rendered frame while recording. */
	public static void onFrame() {
		long now = System.nanoTime();
		// a hitch (loading a chunk, a GC) shouldn't eat seconds of video
		recordedNanos += Math.min(now - lastNanos, 250_000_000L);
		lastNanos = now;
		int target = (int) (recordedNanos * fps / 1_000_000_000L);
		if (target <= written) {
			return;
		}
		int first = written + 1;
		written = target;
		if (PENDING.get() > MAX_PENDING) {
			// the writers are behind: repeat the last frame instead of queueing more pixels
			for (int i = first; i <= target; i++) {
				final int index = i;
				pool.execute(() -> linkToPrevious(index));
			}
			return;
		}
		PENDING.incrementAndGet();
		ExecutorService writers = pool;
		Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(), image -> {
			if (writers.isShutdown()) {
				// the readback landed after stop(): drop it
				image.close();
				PENDING.decrementAndGet();
				return;
			}
			writers.execute(() -> {
				try {
					write(image, first, target);
				} finally {
					image.close();
					PENDING.decrementAndGet();
				}
			});
		});
	}

	private static Path frame(int index) {
		return dir.resolve(String.format("frame_%05d.jpg", index));
	}

	private static void write(NativeImage image, int first, int last) {
		int w = image.getWidth();
		int h = image.getHeight();
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		out.setRGB(0, 0, w, h, image.getPixels(), 0, w);
		Path target = frame(first);
		try {
			ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(0.93f);
			try (ImageOutputStream stream = ImageIO.createImageOutputStream(target.toFile())) {
				writer.setOutput(stream);
				writer.write(null, new IIOImage(out, null, null), param);
			} finally {
				writer.dispose();
			}
			for (int i = first + 1; i <= last; i++) {
				Files.deleteIfExists(frame(i));
				Files.createLink(frame(i), target);
			}
		} catch (IOException e) {
			TrollClient.LOGGER.warn("[showcase] couldn't write frame {}", first, e);
		}
	}

	private static void linkToPrevious(int index) {
		// find the newest frame that exists (writes can finish out of order)
		for (int back = index - 1; back >= Math.max(0, index - 30); back--) {
			if (Files.exists(frame(back))) {
				try {
					Files.deleteIfExists(frame(index));
					Files.createLink(frame(index), frame(back));
				} catch (IOException e) {
					TrollClient.LOGGER.warn("[showcase] couldn't repeat frame {}", index, e);
				}
				return;
			}
		}
	}
}
