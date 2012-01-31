package se.cbb.jprime.seqevo;

import org.ejml.alg.dense.decomposition.DecompositionFactory;
import org.ejml.alg.dense.decomposition.EigenDecomposition;
import org.ejml.data.DenseMatrix64F;
import org.ejml.ops.CommonOps;

import se.cbb.jprime.math.AdditionalEJMLOps;
import se.cbb.jprime.misc.BoundedRealMap;

/**
 * Handles transition probabilities of a Markov process for molecular sequence evolution.
 * Assumes time reversibility, that is, pi_i*mu_ij=pi_j*mu_ji for stationary frequencies pi_x
 * and transition probabilities mu_xy.
 * <p/>
 * The transition rate matrix Q can be decomposed into a symmetric <i>exchangeability</i> matrix R and the vector of 
 * stationary frequencies Pi. The transition probability
 * matrix P over a given (Markov) time interval w is given by 
 * P=exp(Qw). Note that w often is measured in the expected 
 * number of events per site occurring over the interval.
 * 
 * @author Bengt Sennblad.
 * @author Lars Arvestad.
 * @author Joel Sjöstrand.
 */
public class MatrixTransitionHandler {
	
	/** The maximum allowed time w on which transition rate matrix Q can act. */
	public static final double MAX_MARKOV_TIME = 1000.0;

	/** Substitution model name. */
	private String modelName;

	/** The sequence type that is handled, e.g., DNA. TODO: Bens: Can we avoid it? */
	private SequenceType sequenceType;

	/** Number of states in model alphabet, dim. */
	private int alphabetSize;

	/**
	 * The symmetric 'intrinsic rate' matrix of the model (a.k.a. 'exchangeability' matrix).
	 * Represented as a row-major triangular matrix with size (dim*(dim-1)/2,1).
	 * Symmetric values are implicitly defined due to time reversibility assumption.
	 */
	private DenseMatrix64F R;

	/** The stationary frequencies of the model formulated as a diagonal matrix. Only diagonal stored as matrix of size (dim,1). */
	private DenseMatrix64F Pi;
	
	/** The transition rate matrix Q, normalised to have one expected event over branch length one. */
	private DenseMatrix64F Q;

	/** Eigenvalues of the transition rate matrix. Only diagonal, stored as matrix of size (dim,1). */
	private DenseMatrix64F E;

	/** Eigenvectors of the transition rate matrix. Size (dim,dim). */
	private DenseMatrix64F V;

	/** Inverse of V. Size (dim,dim). */
	private DenseMatrix64F iV;

	/** Transition probability matrix (needs to be set for particular (Markov) times. Size (dim,dim). */
	private DenseMatrix64F P;

	/** Temporary storage matrix. Size (dim,dim). */
	private DenseMatrix64F tmp_matrix;

	/** Temporary storage vector. */
	private DenseMatrix64F tmp_diagonal;

	/** A cache for saving instances of P to avoid recalculations. */
	private BoundedRealMap<DenseMatrix64F> PCache;

	/**
	 * Constructor.
	 * @param modelName name of substitution model.
	 * @param sequenceType sequence type.
	 * @param R_vec 'intrinsic' rate matrix, in row-major format. Time reversibility is assumed, therefore, its length should
	 * be dim*(dim-1)/2, where dim is the alphabet length.
	 * @param Pi_vec stationary frequencies. Should have length dim, where dim is the alphabet length.
	 * @param cacheSize number of P matrices to store in cache, e.g., 1000.
	 */
	public MatrixTransitionHandler(String modelName, SequenceType sequenceType, double[] R_vec, double[] Pi_vec, int cacheSize) {
		this.modelName = modelName;
		this.sequenceType = sequenceType;
		this.alphabetSize = sequenceType.getAlphabetSize();
		this.R = new DenseMatrix64F(alphabetSize * (alphabetSize - 1) / 2, 1, true, R_vec);
		// TODO: Do we need to save Pi and R? /bens
		this.Pi = new DenseMatrix64F(alphabetSize, 1, true, Pi_vec);
		this.Q = new DenseMatrix64F(alphabetSize, alphabetSize);
		this.E = new DenseMatrix64F(alphabetSize, 1);
		this.V = new DenseMatrix64F(alphabetSize, alphabetSize);
		this.iV = new DenseMatrix64F(alphabetSize, alphabetSize);
		this.P = new DenseMatrix64F(alphabetSize, alphabetSize);
		this.tmp_matrix = new DenseMatrix64F(alphabetSize, alphabetSize);
		this.tmp_diagonal = new DenseMatrix64F(alphabetSize, 1);
		this.PCache = new BoundedRealMap<DenseMatrix64F>(cacheSize);
		this.update();
	}


	/**
	 * Tests if model and the submitted data are compatible.
	 * This could be used as an early error-catcher.
	 * @param sd sequence data.
	 * @return true if compatible; false if incompatible.
	 */
	public boolean isCompatible(SequenceData sd) {
		return (this.sequenceType == sd.getSequenceType());
	}

	/**
	 * Returns the name of the substitution model.
	 * @return model name.
	 */
	public String getModel() {
		return this.modelName;
	}

	/**
	 * Returns the sequence type that the model handles (DNA, AA, etc.).
	 * @return sequence type.
	 */
	public SequenceType getSequenceType() {
		return this.sequenceType;
	}

	/**
	 * Returns the alphabet size of the Markov process.
	 * @return the size.
	 */
	public int getAlphabetSize() {
		return this.alphabetSize;
	}

	/**
	 * Updates Q and the eigensystem to new values of R and Pi.
	 */
	public void update() {
		// Creates Q by means of R and Pi.
		// The diagonal values of Q = -the sum of other values of row, by definition.
		// R in this implementation holds upper triangle of symmetric matrix, excluding diagonal.
		this.Q.zero();
		int R_i = 0;
		double val;
		for (int i = 0 ; i < alphabetSize; i++) {
			for (int j = i + 1; j < alphabetSize; j++) {
				val = this.Pi.get(i) * this.R.get(R_i);
				this.Q.set(i, j, val);
				this.Q.set(i, i, this.Q.get(i,i) - val);
				// R is symmetric.
				val = this.Pi.get(j) * this.R.get(R_i++);
				this.Q.set(j, i, val);
				this.Q.set(j, j, this.Q.get(j, j) - val);
			}
		}
		
		// Perform scaling of Q so that a branch length of 1 yields 1 expected event.
		double beta = 0;
		for (int i = 0; i < this.alphabetSize; ++i) {
			beta -= this.Pi.get(i) * this.Q.get(i, i);
		}
		beta = 1.0 / beta;
		CommonOps.scale(beta, this.Q);
		
		// Solve eigensystem. NOTE: It is assumed solutions with imaginary parts will never be encountered.
		// To avoid checks, we assume non-symmetric Q. Such models (JC69, etc.) are rarely used in practice anyway.
		EigenDecomposition<DenseMatrix64F> eigFact = DecompositionFactory.eig(this.alphabetSize, true);
		eigFact.decompose(this.Q);
		AdditionalEJMLOps.getEigensystemSolution(this.alphabetSize, eigFact, this.E, this.V);
		// Compute inverse of V.
		CommonOps.invert(this.V, this.iV);
	}


	/**
	 * Sets up P=exp(Qw), the transition probability matrix for the Markov
	 * process over 'time' w (where 'time' is not necessarily temporal).
	 * Precondition: w <= 1000.
	 * @param w the "time" over which Q acts.
	 */
	public void computeTransitionMatrix(double w) {
		// C++ comment which may still apply...  /joelgs
		// If w is too big, the precision of LAPACK seem to get warped!
		// The choice of max value of 1000 is arbitrary and well below the 
		// actual max value. /bens
		// TODO: Could we precondition on a reasonable MarkovTime?
		if (w > MAX_MARKOV_TIME) {
			w = MAX_MARKOV_TIME;
		}

		// Check in cache if result already exists.
		this.P = this.PCache.get(w);
		if (this.P == null) {
			// Nope, we have to create it.
			AdditionalEJMLOps.elementExp(this.alphabetSize, this.E, w, this.tmp_diagonal);
			AdditionalEJMLOps.multDiagA(this.alphabetSize, this.tmp_diagonal, this.iV, this.tmp_matrix);
			this.P = new DenseMatrix64F(this.alphabetSize, this.alphabetSize);
			CommonOps.mult(this.V, this.tmp_matrix, this.P);
			this.PCache.put(w, this.P);
		}
	}

	/**
	 * Performs matrix multiplication Y=P*X.
	 * @param X operand matrix (typically vector) of size (dim,ncol).
	 * @param Y resulting matrix Y=P*X. Should have size (dim,ncol).
	 */
	public void multiplyWithP(DenseMatrix64F X, DenseMatrix64F Y) {
		CommonOps.mult(this.P, X, Y);
	}

	/**
	 * Returns a column from P.
	 * @param colum the column index.
	 * @param result the column values. Should have size (dim,1).
	 */
	public void getColumnFromP(int column, DenseMatrix64F result) {
		for (int i = 0; i < this.alphabetSize; ++i) {
			result.set(i, this.P.get(i, column));
		}
	}

	/**
	 * Element-wise multiplication Y=Pi*X.
	 * @param X operand matrix (typically vector) of size (dim,ncol).
	 * @param Y resulting matrix Y=Pi*X. Should have size (dim,ncol).
	 */
	public void multiplyWithPi(DenseMatrix64F X, DenseMatrix64F Y) {
		CommonOps.mult(this.Pi, X, Y);
	}


	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("Markov transition rate matrix of model ").append(modelName).append('\n');
		sb.append("Current symmetric intrinsic rate matrix, R (flattened):\n");
		sb.append(this.R.toString()).append('\n');
		sb.append("Current stationary distribution base frequencies, Pi:\n");
		sb.append(this.Pi.toString()).append('\n');
		sb.append("Current eigenvalue matrix of Q, E:\n");
		sb.append(this.E.toString()).append('\n');
		sb.append("Current right-hand side eigenvectors of Q, V:\n");
		sb.append(this.V.toString()).append('\n');
		sb.append("Current inverse of V, iV:\n");
		sb.append(this.iV.toString()).append('\n');
		return sb.toString();
	};


	/**
	 * For debugging and similar purposes, returns R in a String format.
	 * @return the R matrix.
	 */
	public String getRString() {
		StringBuilder sb = new StringBuilder();
		int R_index = 0;
		sb.append("Alphabet_size: ").append(alphabetSize).append('\n');
		for (int i = 0; i < alphabetSize; i++) {
			for (int j = 0; j < alphabetSize; j++) {
				if (j < alphabetSize) {
					sb.append('\t');
				}
				if (j > i) {
					sb.append(this.R.get(R_index++));
				}
			}
			if (i < alphabetSize - 2) {
				sb.append('\n');
			}
		}
		return sb.toString();
	}
}
