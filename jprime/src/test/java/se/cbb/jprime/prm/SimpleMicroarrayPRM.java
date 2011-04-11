package se.cbb.jprime.prm;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Scanner;
import org.junit.* ;
import org.uncommons.maths.random.MersenneTwisterRNG;
import se.cbb.jprime.math.IntegerInterval;
import se.cbb.jprime.math.Probability;
import se.cbb.jprime.prm.ProbAttribute.DependencyConstraints;
import se.cbb.jprime.prm.Relation.Type;

import static org.junit.Assert.*;

/**
 * Simple microarray PRM example.
 * 
 * @author Joel Sjöstrand.
 */
public class SimpleMicroarrayPRM {

	@Test
	public void main() throws FileNotFoundException {
		// Test skeleton.
		assertEquals(1000, this.skeleton.getPRMClass("Gene").getNoOfEntities());
		assertEquals(80, this.skeleton.getPRMClass("Array").getNoOfEntities());
		assertEquals(1000 * 80, this.skeleton.getPRMClass("Measurement").getNoOfEntities());
		
		this.run();
	}

	private MersenneTwisterRNG rng;
	
	private Skeleton skeleton;
	
	public SimpleMicroarrayPRM() throws FileNotFoundException {
		this.rng = new MersenneTwisterRNG();
		System.out.println(this.rng.getSeed().length);
		this.skeleton = new Skeleton("SimpleMicroarraySkeleton");
		
		// Fill skeleton.
		this.readGeneFile();
		this.readArrayFiles();
		this.readMeasurementFiles();
	}
	
	/**
	 * Reads the gene file. Sets the hidden variable to 0 by default.
	 * @throws FileNotFoundException.
	 */
	public void readGeneFile() throws FileNotFoundException {
		// Skeleton part.
		PRMClass genes = new PRMClass("Gene");
		FixedAttribute id = new FixedAttribute("ID", genes, 1024);
		BooleanAttribute a1 = new BooleanAttribute("A1", genes, false, 1024, DependencyConstraints.NONE);
		BooleanAttribute a2 = new BooleanAttribute("A2", genes, false, 1024, DependencyConstraints.NONE);
		BooleanAttribute a3 = new BooleanAttribute("A3", genes, false, 1024, DependencyConstraints.NONE);
		IntegerInterval clusterRange = new IntegerInterval(1,12);
		IntAttribute cluster = new IntAttribute("Cluster", genes, true, 1024, DependencyConstraints.PARENT_ONLY,
				clusterRange);
		this.skeleton.addPRMClass(genes);
		
		// Read values. Assign random values to latent variable.
		System.out.println(this.getClass().getResource("."));
		File f = new File(this.getClass().getResource("/microarray/synthetic/genesAttributes.out").getFile());
		Scanner sc = new Scanner(f);
		while (sc.hasNextLine()) {
			String ln = sc.nextLine().trim();
			if (ln.equals("")) { continue; }
			String[] parts = ln.split(",");
			id.addEntity(parts[0]);
			a1.addEntity(parts[1].contains("A1"));
			a2.addEntity(parts[1].contains("A2"));
			a3.addEntity(parts[1].contains("A3"));
			cluster.addEntity(clusterRange.getRandom(this.rng));
		}
		sc.close();
	}
	
	/**
	 * Reads the microarray file.
	 * @throws FileNotFoundException.
	 */
	private void readArrayFiles() throws FileNotFoundException {
		// Skeleton part
		PRMClass arrays = new PRMClass("Array");
		FixedAttribute id = new FixedAttribute("ID", arrays, 128);
		IntAttribute cluster = new IntAttribute("Cluster", arrays, false, 128, DependencyConstraints.PARENT_ONLY,
				new IntegerInterval(1, 4));
		this.skeleton.addPRMClass(arrays);
		
		// Read values.
		File f = new File(this.getClass().getResource("/microarray/synthetic/ArrayCluster.out").getFile());
		Scanner sc = new Scanner(f);
		while (sc.hasNextLine()) {
			String ln = sc.nextLine().trim();
			if (ln.equals("")) { continue; }
			String[] parts = ln.split("[\t ]+");
			id.addEntity(parts[0]);
			cluster.addEntity(Integer.parseInt(parts[1]));
		}
		sc.close();
	}

	/**
	 * Reads the expression level files.
	 * @throws FileNotFoundException.
	 */
	private void readMeasurementFiles() throws FileNotFoundException {
		// Skeleton part.
		PRMClass measurements = new PRMClass("Measurement");
		FixedAttribute id = new FixedAttribute("ID", measurements, 131072);
		FixedAttribute gID = new FixedAttribute("GeneID", measurements, 131072);
		FixedAttribute aID = new FixedAttribute("ArrayID", measurements, 131072);
		IntAttribute level = new IntAttribute("Level", measurements, false, 131072, DependencyConstraints.NONE,
				new IntegerInterval(-1, 1));
		PRMClass genes = this.skeleton.getPRMClass("Gene");
		PRMClass arrays = this.skeleton.getPRMClass("Array");
		// Relations add themselves to their classes...
		new Relation(gID, genes.getFixedAttribute("ID"), Type.MANY_TO_ONE, true);
		new Relation(aID, arrays.getFixedAttribute("ID"), Type.MANY_TO_ONE, true);
		this.skeleton.addPRMClass(measurements);
		
		// Read values.
		File f;
		Scanner sc;
		// One file per array (80 in total).
		for (int i = 0; i < 80; ++i) {
			f = new File(this.getClass().getResource("/microarray/synthetic/exp_array_" + i + ".out").getFile());
			sc = new Scanner(f);
			String[] lvls = sc.nextLine().trim().split(",");
			// One expression level per gene (1000 in total).
			for (int j = 0; j < 1000; ++j) {
				id.addEntity("G" + j + "-" + "A" + i);
				gID.addEntity("G" + j);
				aID.addEntity("A" + i);
				level.addEntity(Integer.parseInt(lvls[j]));
			}
			sc.close();
		}
	}
	
	/** TBD. */
	public Structure getTrueStructure() {
		// True structure.
		Structure s = new Structure(this.skeleton);
		ProbAttribute gc = this.skeleton.getPRMClass("Gene").getProbAttribute("Cluster");
		ProbAttribute a1 = this.skeleton.getPRMClass("Gene").getProbAttribute("A1");
		ProbAttribute a2 = this.skeleton.getPRMClass("Gene").getProbAttribute("A2");
		ProbAttribute a3 = this.skeleton.getPRMClass("Gene").getProbAttribute("A3");
		ProbAttribute ac = this.skeleton.getPRMClass("Array").getProbAttribute("Cluster");
		ProbAttribute lvl = this.skeleton.getPRMClass("Measurement").getProbAttribute("Level");
		Relation m2g = this.skeleton.getPRMClass("Measurement").getRelation("Measurement.GeneID-Gene.ID");
		Relation m2a = this.skeleton.getPRMClass("Measurement").getRelation("Measurement.ArrayID-Array.ID");
		ArrayList<Relation> sc = new ArrayList<Relation>(1);
		sc.add(m2g);
		s.putDependency(new Dependency(lvl, sc, gc, true));
		s.putDependency(new Dependency(lvl, sc, a1, true));
		s.putDependency(new Dependency(lvl, sc, a2, true));
		s.putDependency(new Dependency(lvl, sc, a3, true));
		sc = new ArrayList<Relation>(1);
		sc.add(m2a);
		s.putDependency(new Dependency(lvl, sc, ac, true));
		return s;
	}
		
	
	private void run() {
//		for (int i = 0; i < 5000; ++i) {
//			Structure s = RandomStructureGenerator.createStrictRandomStructure(this.rng, this.skeleton, 12, 5, 2, 200);
//			if (!this.structures.contains(s)) {
//				this.structures.add(s);
//				//System.out.println(s);
//			}
//		}
//		for (Structure s : this.structures) {
//			System.out.println(s);
//		}
//		System.out.println(this.structures.size());
		
		DependenciesCache<DirichletCounts> counts = new DependenciesCache<DirichletCounts>();
		ArrayList<Dependencies> toAdd = new ArrayList<Dependencies>();
		ArrayList<Dependencies> toUpdate = new ArrayList<Dependencies>();
		
		// Start with true structure.
		Structure struct = this.getTrueStructure();
		
		double loglhood = -1000000000;
		int nonImpr = 0;
		while (nonImpr < 5) {
			counts.getNonCached(struct, toAdd, toUpdate);
			for (Dependencies deps : toAdd) {
				try {
					int noOfSamples = deps.getChild().getNoOfEntities();
					int noOfPosVals = Math.max(1, deps.getParentCardinality()) * deps.getChildCardinality();
					double pseudoCnt = ((double) noOfSamples / noOfPosVals) / 10;
					counts.put(deps, new DirichletCounts(deps, pseudoCnt));
				} catch (Exception ex) {}
			}
			for (Dependencies deps : toUpdate) {
				counts.get(deps).update();
			}
			this.inferGeneClusters(struct, counts);
			Probability p = new Probability(1.0);
			for (Dependencies deps : struct.getDependencies()) {
				p.mult(counts.get(deps).getLikelihood());
			}
			
			// Count the number of consecutive non-improvements.
			if (p.getLogValue() <= loglhood) {
				++nonImpr;
			} else {
				nonImpr = 0;
			}
			loglhood = p.getLogValue();
			System.out.println(loglhood);
		}
		ProbAttribute gc = struct.getSkeleton().getPRMClass("Gene").getProbAttribute("Cluster");
		System.out.println(gc);
	}
	
	/**
	 * Makes a soft completion inference of gene cluster, assuming it is the only latent attribute and
	 * that is is a source in the induced BN. Used instead of implementing a complete belief
	 * propagation or similarly.
	 * @param struct the structure.
	 * @param counts the counts (and thus conditional probabilities of the dependencies).
	 */
	private void inferGeneClusters(Structure struct, DependenciesCache<DirichletCounts> counts) {
		// Obtain all dependencies of which gene cluster is a parent.
		DiscreteAttribute gc = (DiscreteAttribute) struct.getSkeleton().getPRMClass("Gene").getProbAttribute("Cluster");
		Collection<Dependencies> gcDeps = struct.getInverseDependencies(gc);
		ArrayList<DirichletCounts> gcCounts = new ArrayList<DirichletCounts>(gcDeps.size());
		for (Dependencies deps : gcDeps) {
			gcCounts.add(counts.get(deps));
		}
		
		// Clear the soft completions.
		for (int i = 0; i < gc.getNoOfEntities(); ++i) {
			gc.clearEntityProbDistribution(i);
		}
		
		// For each child attribute.
		for (Dependencies deps : gcDeps) {
			// Retrieve child 'ch' and the dependency 'gc->ch', etc.
			ProbAttribute ch = deps.getChild();
			DirichletCounts dc = counts.get(deps);
			Dependency gcDep = null;
			for (Dependency dep : deps.getAll()) {
				if (dep.getParent() == gc) {
					gcDep = dep;  // There can be only one in our case.
					break;
				}
			}
			
			// For each child entity.
			for (int i = 0; i < ch.getNoOfEntities(); ++i) {
				
				// Get gene cluster entity and its soft completion.
				int gcIdx = gcDep.getSingleParentEntity(i);
				double[] sc = gc.getEntityProbDistribution(gcIdx);
				
				// For each possible value of the gene cluster entity,
				// make a temporary hard assignment, then update soft completion.
				for (int j = 0; j < 12; ++j) {
					gc.setEntityAsNormalisedInt(gcIdx, j);
					double p = dc.getExpectedConditionalProb(i);
					sc[j] = (Double.isNaN(sc[j]) ? p : sc[j] * p);  // NaN if uninitialised.
				}
			}
		}
		
		// Finally, normalise our soft completions.
		for (int i = 0; i < gc.getNoOfEntities(); ++i) {
			gc.normaliseEntityProbDistribution(i);
		}
	}
}
