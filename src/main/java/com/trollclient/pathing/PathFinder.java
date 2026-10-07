package com.trollclient.pathing;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Walking-only A*: no block breaking or placing. Moves are flat steps (with
 * diagonals that don't clip corners), one-block step-ups and drops of up to
 * {@code maxFall} blocks. Searches are capped by node count and wall time so a
 * hopeless goal can never freeze the game.
 */
public final class PathFinder {
	private static final int[][] DIRS = {
			{1, 0}, {-1, 0}, {0, 1}, {0, -1},
			{1, 1}, {1, -1}, {-1, 1}, {-1, -1}
	};

	public record Options(int maxNodes, long maxMillis, int maxFall, boolean allowStepUp) {
		public static Options defaults() {
			return new Options(4000, 6, 3, true);
		}
	}

	private static final class Node implements Comparable<Node> {
		final BlockPos pos;
		Node parent;
		double g;
		double h;
		boolean closed;

		Node(BlockPos pos) {
			this.pos = pos;
		}

		double f() {
			return g + h;
		}

		@Override
		public int compareTo(Node o) {
			return Double.compare(f(), o.f());
		}
	}

	private PathFinder() {
	}

	/**
	 * @param goal      accepts the node that ends the search
	 * @param heuristic estimated remaining cost (also used to pick the best partial path)
	 * @param extraCost additional cost for standing at a position (e.g. near enemies); may be null
	 * @return feet positions from (excluding) start to goal, or the best partial path; never null
	 */
	public static List<BlockPos> find(Level level, BlockPos start, Predicate<BlockPos> goal,
									  ToDoubleFunction<BlockPos> heuristic, ToDoubleFunction<BlockPos> extraCost,
									  Options options) {
		long deadline = System.nanoTime() + options.maxMillis() * 1_000_000L;
		Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>();

		Node startNode = new Node(start);
		startNode.h = heuristic.applyAsDouble(start);
		nodes.put(start.asLong(), startNode);
		open.add(startNode);
		Node best = startNode;
		int expanded = 0;

		while (!open.isEmpty()) {
			Node current = open.poll();
			if (current.closed) {
				continue;
			}
			current.closed = true;
			if (goal.test(current.pos)) {
				return build(current);
			}
			if (current.h < best.h) {
				best = current;
			}
			if (++expanded > options.maxNodes() || (expanded % 64 == 0 && System.nanoTime() > deadline)) {
				break;
			}
			for (Move move : neighbours(level, current.pos, options)) {
				double extra = extraCost == null ? 0 : extraCost.applyAsDouble(move.pos);
				double g = current.g + move.cost + extra;
				long key = move.pos.asLong();
				Node n = nodes.get(key);
				if (n == null) {
					n = new Node(move.pos);
					n.h = heuristic.applyAsDouble(move.pos);
					nodes.put(key, n);
				} else if (n.closed || g >= n.g) {
					continue;
				}
				n.g = g;
				n.parent = current;
				open.add(n);
			}
		}
		return build(best);
	}

	private record Move(BlockPos pos, double cost) {
	}

	private static List<Move> neighbours(Level level, BlockPos from, Options options) {
		List<Move> moves = new ArrayList<>(12);
		boolean headroom = Walkability.isPassable(level, from.above(2));
		for (int[] d : DIRS) {
			int dx = d[0];
			int dz = d[1];
			boolean diagonal = dx != 0 && dz != 0;
			BlockPos flat = from.offset(dx, 0, dz);
			if (diagonal) {
				// both orthogonal neighbours must be clear or we'd clip the corner
				BlockPos a = from.offset(dx, 0, 0);
				BlockPos b = from.offset(0, 0, dz);
				if (!bodyFits(level, a) || !bodyFits(level, b)) {
					continue;
				}
			}
			double base = diagonal ? 1.4142 : 1.0;
			if (Walkability.isStandable(level, flat)) {
				moves.add(new Move(flat, base + waterPenalty(level, flat)));
				continue;
			}
			if (!diagonal && options.allowStepUp() && headroom) {
				BlockPos up = flat.above();
				if (Walkability.isStandable(level, up)) {
					moves.add(new Move(up, base + 1.2));
					continue;
				}
			}
			if (bodyFits(level, flat)) {
				for (int fall = 1; fall <= options.maxFall(); fall++) {
					BlockPos down = flat.below(fall);
					if (Walkability.isStandable(level, down)) {
						moves.add(new Move(down, base + fall * 0.6));
						break;
					}
					if (!Walkability.isPassable(level, down)) {
						break;
					}
				}
			}
		}
		return moves;
	}

	private static boolean bodyFits(Level level, BlockPos pos) {
		return Walkability.isPassable(level, pos) && Walkability.isPassable(level, pos.above());
	}

	private static double waterPenalty(Level level, BlockPos pos) {
		return Walkability.isWater(level, pos) ? 2.5 : 0;
	}

	private static List<BlockPos> build(Node end) {
		List<BlockPos> path = new ArrayList<>();
		for (Node n = end; n != null && n.parent != null; n = n.parent) {
			path.add(n.pos);
		}
		Collections.reverse(path);
		return path;
	}
}
