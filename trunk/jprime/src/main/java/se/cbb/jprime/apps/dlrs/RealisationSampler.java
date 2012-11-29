package se.cbb.jprime.apps.dlrs;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import se.cbb.jprime.io.SampleInt;
import se.cbb.jprime.io.Sampleable;
import se.cbb.jprime.math.PRNG;
import se.cbb.jprime.mcmc.Iteration;
import se.cbb.jprime.topology.BooleanMap;
import se.cbb.jprime.topology.DoubleArrayMap;
import se.cbb.jprime.topology.IntMap;
import se.cbb.jprime.topology.NamesMap;
import se.cbb.jprime.topology.RBTreeArcDiscretiser;
import se.cbb.jprime.topology.RootedBifurcatingTree;
import se.cbb.jprime.topology.StringMap;
import se.cbb.jprime.topology.TimesMap;

/**
 * Enables sampling of <i>realisations</i>, i.e., dated embeddings
 * of G in S according to the probability distribution of embeddings under the DLRS model.
 * 
 * @author Joel Sjöstrand.
 */
public class RealisationSampler implements Sampleable {
	
	/** Output stream. */
	private BufferedWriter out;
	
	/** Iteration. */
	private Iteration iteration;
	
	/** PRNG. */
	private PRNG prng;
	
	/** Host tree. */
	private RootedBifurcatingTree S;
	
	/** Guest tree. */
	private RootedBifurcatingTree G;
	
	/** Guest tree names. */
	private NamesMap names;
	
	/** Times for S and discretisation times for S'. */
	private RBTreeArcDiscretiser times;
	
	/** Lower limits for placement of vertices v of G in S'. */
	private IntMap loLims;
	
	/** P11, etc. */
	private DupLossProbs dupLossProbs;
	
	/** At-probabilities for vertices v of G. */
	private DoubleArrayMap atsProbs;
	
	/** No. of realisations per sampling round. */
	private int noOfRealisations;
	
	/**
	 * Constructor.
	 * @param file f the output str.
	 * @param iteration iteration.
	 * @param prng pseudo-random number generator.
	 * @param S host tree S.
	 * @param G guest tree G.
	 * @param names leaf names of G.
	 * @param times times of discretised host tree S'.
	 * @param loLims lowest possible placement of u of V(G) in discretised S'.
	 * @param dupLossProbs p11, etc.
	 * @param ats rooted subtree G_u probability for u of V(G).
	 * @param noOfRealisations number of realisations per sampling round.
	 * @throws IOException.
	 */
	public RealisationSampler(String filename, int noOfRealisations, Iteration iteration, PRNG prng, DLRSModel model, NamesMap names) throws IOException {
		this.out = new BufferedWriter(new FileWriter(filename));
		this.noOfRealisations = noOfRealisations;
		this.iteration = iteration;
		this.prng = prng;
		this.S = model.s;
		this.G = model.g;
		this.names = names;
		this.times = model.reconcHelper.times;
		this.loLims = model.reconcHelper.loLims;
		this.dupLossProbs = model.dupLossProbs;
		this.atsProbs = model.ats;
		
		// Write header.
		this.out.write("# Host tree: " + this.times.toString() + "\n");
		if (this.noOfRealisations > 0) {
			this.out.write("RealisationID\tSubsample\tRealisation\n");
		}
	}
	

	/**
	 * Samples a realisation given the current guest tree, "at-probabilities", p11-probabilities, etc.
	 */
	public Realisation sample() {
				
		// Vertices of G in topological ordering from root to leaves.
		List<Integer> vertices = this.G.getTopologicalOrdering();
		
		int n = vertices.size();
		int[][] placements = new int[n][];  // Sampled points.
		double[] abst = new double[n];      // Absolute times.
		double[] arct = new double[n];      // Arc times.
		boolean[] isDups = new boolean[n];  // Type of point.
		
		// For each vertex v of G.
		String[] placementss = new String[n];
		for (int v : vertices) {
			samplePoint(v, placements, abst, arct, isDups);
			placementss[v] = "(" + placements[v][0] + "," + placements[v][1] + ")"; 
		}
		
		// Finally, generate guest tree with times.
		return new Realisation(this.G, this.names, new TimesMap("RealisationTimes", abst, arct), new BooleanMap("RealisationIsDups", isDups), new StringMap("DiscPts",placementss));
	}
	
	/**
	 * Samples a point y in S' for placement of vertex v of G, given that the parent of v has been sampled already.
	 * @param v vertex of G.
	 * @param placements placements in S'
	 * @param absTimes absolute times of sampled tree.
	 * @param arcTimes arc times of sampled tree.
	 * @param isDups type of point.
	 */
	private void samplePoint(int v, int[][] placements, double[] absTimes, double[] arcTimes, boolean[] isDups) {
		
		// Get placement of parent of v in S'.
		int[] x;
		if (this.G.isRoot(v)) {
			x = new int[2];
			x[0] = this.S.getRoot();
			x[1] = this.times.getNoOfSlices(x[0]) + 1;
		} else {
			x = placements[this.G.getParent(v)];
		}
		
		// Lowest valid placement of v in S'.
		int[] y = RealisationSampler.getProperLolim(loLims.get(v));
		
		if (!this.G.isLeaf(v)) {
			
			int i = 0;             // Current point.
			double tot = 0.0;      // Current cumulative probability.
			double[] ats = this.atsProbs.get(v);
			
			// Stores all valid placement y's.
			ArrayList<int[]> ys = new ArrayList<int[]>(ats.length);
			
			// Cumulative probabilities for the y's.
			ArrayList<Double> cps = new ArrayList<Double>(ats.length);
					
			// Compute relative cumulative probabilities for all valid placements y beneath x.
			while (i < ats.length && !(x[0] == y[0] && x[1] <= y[1])) {
				double p = this.dupLossProbs.getP11Probability(x[0], x[1], y[0], y[1]) * ats[i];
				tot += p;
				ys.add(y);
				cps.add(tot);
				
				// Move to point above.
				++i;
				if (y[1] == times.getNoOfSlices(y[0])) {
					y = new int[] { S.getParent(y[0]), 1 };  // Onto next arc.
				} else {
					y = new int[] { y[0], y[1]+1 };
				}
			}
			
			// Sample a point in the host tree.
			if (tot < 1e-256) {
				// No signal: choose a point uniformly.
				int idx = this.prng.nextInt(ys.size());
				y = ys.get(idx);
			} else {
				// Sample according to probabilities of placements.
				double rnd = this.prng.nextDouble() * tot;
				int idx = 0;
				while (cps.get(idx) < rnd && idx < ys.size()) {
					++idx;
				}
				y = ys.get(idx);
			}
		}
		
		// Finally, store the properties.
		placements[v] = y;
		absTimes[v] = this.times.getDiscretisationTime(y[0], y[1]);
		arcTimes[v] = this.times.getDiscretisationTime(x[0], x[1]) - absTimes[v];
		isDups[v] = (y[1] > 0);    // 0 for speciations and leaves.
	}
	
	/**
	 * Closes the underlying buffer.
	 * @throws IOException 
	 */
	public void close() throws IOException {
		this.out.close();
	}
	
	/**
	 * Flushes the underlying buffer.
	 * @throws IOException 
	 */
	public void flush() throws IOException {
		this.out.flush();
	}
	
	/**
	 * Returns a proper representation of a lower limit.
	 * @param loLim the lower limit, holding arc and discretisation point in one int.
	 * @return [arc in S, discretisation point].
	 */
	private static int[] getProperLolim(int loLim) {
		int[] prop = new int[2];
		prop[0] = ((loLim << 16) >>> 16);   // Arc (=head vertex of arc).
		prop[1] = (loLim >>> 16);           // Discretisation point.
		return prop;
	}

	@Override
	public Class<?> getSampleType() {
		return SampleInt.class;
	}


	@Override
	public String getSampleHeader() {
		return "RealisationID";
	}


	@Override
	public String getSampleValue() {
		
		// Use current iteration as ID.
		String id = "" + this.iteration.getIteration(); 
		
		// Do sampling to own file.
		for (int i = 0; i < this.noOfRealisations; ++i) {
			try {
				this.out.write(id);
				this.out.write('\t');
				this.out.write("" + i);
				this.out.write('\t');
				Realisation real = this.sample();
				this.out.write(real.toString());
				this.out.write('\n');
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
				
		return id;
	}

}