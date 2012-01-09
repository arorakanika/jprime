package se.cbb.jprime.apps.gsrf;

import java.io.BufferedWriter;

import se.cbb.jprime.io.RBTreeSampleWrapper;
import se.cbb.jprime.io.SampleDoubleArray;
import se.cbb.jprime.io.SampleWriter;
import se.cbb.jprime.math.Continuous1DPDDependent;
import se.cbb.jprime.math.PRNG;
import se.cbb.jprime.mcmc.DoubleParameter;
import se.cbb.jprime.mcmc.Iteration;
import se.cbb.jprime.mcmc.MCMCManager;
import se.cbb.jprime.mcmc.MultiProposerSelector;
import se.cbb.jprime.mcmc.NormalProposer;
import se.cbb.jprime.mcmc.ProposalAcceptor;
import se.cbb.jprime.mcmc.Thinner;
import se.cbb.jprime.misc.Triple;
import se.cbb.jprime.topology.DoubleMap;
import se.cbb.jprime.topology.GuestHostMap;
import se.cbb.jprime.topology.MPRMap;
import se.cbb.jprime.topology.NamesMap;
import se.cbb.jprime.topology.RBTree;
import se.cbb.jprime.topology.RBTreeArcDiscretiser;
import se.cbb.jprime.topology.RBTreeBranchSwapper;
import se.cbb.jprime.topology.TimesMap;

import com.beust.jcommander.JCommander;

/**
 * Java version of the GSRf application for simultaneous reconciliation and inference
 * of a guest tree evolving inside a dated host tree.
 * 
 * @author Joel Sjöstrand.
 */
public class GSRf {
	
	/**
	 * GSRf starter.
	 * @param args.
	 */
	public static void main(String[] args) {
		try {
			
			// ================ PARSE USER OPTIONS AND ARGUMENTS ================
			
			Parameters params = new Parameters();
			JCommander jc = new JCommander(params, args);
			if (args.length == 0 || params.help) {
				jc.usage();
				return;
			}
			
			// ================ READ AND CREATE ALL PARAMETERS ================
			
			// Read S and t.
			Triple<RBTree, NamesMap, TimesMap> sNamesTimes = ParameterParser.getHostTree(params);
			
			// Read guest-to-host leaf map.
			GuestHostMap gsMap = ParameterParser.getGSMap(params);
			
			// MCMC chain output and auxiliary info.
			SampleWriter sampler = ParameterParser.getOut(params);
			BufferedWriter info = ParameterParser.getInfo(params);
			
			// Pseudo-random number generator.
			PRNG prng = ParameterParser.getPRNG(params);
			
			// Read/create G and l.
			Triple<RBTree, NamesMap, DoubleMap> gNamesLengths = ParameterParser.getGuestTreeAndLengths(params, gsMap, prng, null);
			
			// Read number of iterations and thinning factor.
			Iteration iter = ParameterParser.getIteration(params);
			Thinner thinner = ParameterParser.getThinner(params, iter);
			
			// Sigma (mapping between G and S).
			MPRMap mprMap = new MPRMap(gsMap, gNamesLengths.first, gNamesLengths.second, sNamesTimes.first, sNamesTimes.second);
			
			// Read probability distribution for iid guest tree edge rates (molecular clock relaxation). 
			Triple<DoubleParameter, DoubleParameter, Continuous1DPDDependent> edgeRatePD = ParameterParser.getEdgeRatePD(params);
			
			// Create discretisation of S.
			RBTreeArcDiscretiser dtimes = ParameterParser.getDiscretizer(params, sNamesTimes.first, sNamesTimes.third, gNamesLengths.first);
			
			// Duplication-loss probabilities over discretised S.
			Triple<DoubleParameter, DoubleParameter, DupLossProbs> dupLoss = ParameterParser.getDupLossProbs(params, mprMap, sNamesTimes.first, dtimes);
			
			// ================ CREATE MODELS, PROPOSERS, ETC. ================
			
			// GSRf model.
			GSRfModel gsrf = new GSRfModel(gNamesLengths.first, sNamesTimes.first, mprMap, gNamesLengths.third, dtimes, dupLoss.third, edgeRatePD.third);
			
			// Proposers.
			NormalProposer dupRateProposer = ParameterParser.getNormalProposer(params, dupLoss.first, iter, prng, params.tuningDupRate);
			NormalProposer lossRateProposer = ParameterParser.getNormalProposer(params, dupLoss.second, iter, prng, params.tuningLossRate);
			NormalProposer edgeRateMeanProposer = ParameterParser.getNormalProposer(params, edgeRatePD.first, iter, prng, params.tuningEdgeRateMean);
			NormalProposer edgeRateCVProposer = ParameterParser.getNormalProposer(params, edgeRatePD.first, iter, prng, params.tuningEdgeRateCV);
			RBTreeBranchSwapper guestTreeProposer = ParameterParser.getBranchSwapper(gNamesLengths.first, gNamesLengths.third, iter, prng);
			double[] moves = SampleDoubleArray.toDoubleArray(params.tuningGuestTreeMoveWeights);
			guestTreeProposer.setOperationWeights(moves[0], moves[1], moves[2]);
			NormalProposer lengthsProposer = ParameterParser.getNormalProposer(params, gNamesLengths.third, iter, prng, params.tuningLengths);
			lengthsProposer.setSubParameterWeights(SampleDoubleArray.toDoubleArray(params.tuningLengthsSelectorWeights));
			
			// Proposer selector.
			MultiProposerSelector selector = ParameterParser.getSelector(params, prng);
			selector.add(dupRateProposer, ParameterParser.getProposerWeight(params.tuningWeightDupRate, iter));
			selector.add(lossRateProposer, ParameterParser.getProposerWeight(params.tuningWeightLossRate, iter));
			selector.add(edgeRateMeanProposer, ParameterParser.getProposerWeight(params.tuningWeightEdgeRateMean, iter));
			selector.add(edgeRateCVProposer, ParameterParser.getProposerWeight(params.tuningWeightEdgeRateCV, iter));
			selector.add(guestTreeProposer, ParameterParser.getProposerWeight(params.tuningWeightG, iter));
			selector.add(lengthsProposer, ParameterParser.getProposerWeight(params.tuningWeightLengths, iter));
			
			// Inactivate fixed proposers.
			if (params.dupRate.matches("FIXED|Fixed|fixed"))        { dupRateProposer.setEnabled(false); }
			if (params.lossRate.matches("FIXED|Fixed|fixed"))       { lossRateProposer.setEnabled(false); }
			if (params.edgeRatePDMean.matches("FIXED|Fixed|fixed")) { edgeRateMeanProposer.setEnabled(false); }
			if (params.edgeRatePDCV.matches("FIXED|Fixed|fixed"))   { edgeRateCVProposer.setEnabled(false); }
			if (params.guestTreeFixed)                              { guestTreeProposer.setEnabled(false); }
			if (params.lengthsFixed)                                { lengthsProposer.setEnabled(false); }
			
			// Proposal acceptor.
			ProposalAcceptor acceptor = ParameterParser.getAcceptor(params, prng);
			
			// ================ SETUP MCMC HIERARCHY ================
			
			MCMCManager manager = new MCMCManager(iter, thinner, selector, acceptor, sampler, prng);
			
			manager.addModel(gsrf);
			
			manager.addSampleable(iter);
			manager.addSampleable(manager);			// Overall likelihood.
			manager.addSampleable(gsrf);
			manager.addSampleable(dupLoss.first);
			manager.addSampleable(dupLoss.second);
			manager.addSampleable(edgeRatePD.first);
			manager.addSampleable(edgeRatePD.second);
			manager.addSampleable(new RBTreeSampleWrapper(gNamesLengths.first, gNamesLengths.second));
			if (params.outputLengths) {
				manager.addSampleable(new RBTreeSampleWrapper(gNamesLengths.first, gNamesLengths.second, gNamesLengths.third));
			}
			
			// ================ RUN ================
			
			manager.writePreInfo(info, true);
			manager.run();
			sampler.close();
			manager.writePostInfo(info, true);
			
		} catch (Exception e) {
			System.err.print(e);
			System.err.print("\nUse option -h or --help to show usage.");
		}
	}
	
	
}