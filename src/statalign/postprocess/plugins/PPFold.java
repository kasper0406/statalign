package statalign.postprocess.plugins;

import java.awt.BorderLayout;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import com.ppfold.algo.AsynchronousJobExecutor;
import com.ppfold.algo.AsynchronousJobExecutorThreadPool;
import com.ppfold.algo.ExportTools;
import com.ppfold.algo.FoldingProject;
import com.ppfold.algo.MatrixTools;
import com.ppfold.algo.NeighbourJoining;
import com.ppfold.algo.NullProgress;
import com.ppfold.algo.Parameters;
import com.ppfold.algo.Progress;
import com.ppfold.algo.ResultBundle;
import com.ppfold.algo.Tree;
import com.ppfold.algo.extradata.ExtraData;
import com.ppfold.main.Alignment;
import com.ppfold.main.AlignmentReader;
import com.ppfold.main.DataInfo;
import com.ppfold.main.NewickReader;
import com.ppfold.main.PPfoldMain;

import statalign.base.InputData;
import statalign.base.Mcmc;
import statalign.base.State;
import statalign.base.Utils;
import statalign.distance.Distance;
import statalign.postprocess.Postprocess;
import statalign.postprocess.gui.AlignmentGUI;
import statalign.postprocess.plugins.benchmarks.Benchmarks;
import statalign.postprocess.gui.PPFoldGUI;
import statalign.postprocess.utils.Mapping;
import statalign.postprocess.utils.RNAFoldingTools;

public class PPFold extends statalign.postprocess.Postprocess {
	
	public static void main(String [] args)
	{
		int [] pairedSites = RNAFoldingTools.getPosteriorDecodingConsensusStructure(RNAFoldingTools.loadMatrix(new File("bp_log.matrix")));
		System.out.println(RNAFoldingTools.getDotBracketStringFromPairedSites(pairedSites));
	}
	
	// variables from ppfoldmain

	static private Progress progress = NullProgress.INSTANCE;; // Progressbar;
																// either
																// NullActivity
																// (if no GUI),
																// or the
																// PPfoldProgressBar
																// (if GUI)

	// end of variables from ppfoldmain

	public String title;
	//public int frequency = 5;
	JPanel pan = new JPanel(new BorderLayout());
	PPFoldGUI gui;
	// private boolean sampling = true;

	CurrentAlignment curAlig;
	MpdAlignment mpdAlignment;

	ColumnNetwork network;
	Column firstVector, lastVector;
	int sizeOfAlignments;

	int[] firstDescriptor;
	static String t[][];
	String[] sequences;
	String[] viterbialignment;
	int d;

	int seqNo = 0;
	static String refSeqName = "";
	String refSeq;
	String refSeqGapped;

	float[][] summedArray;
	float[] summedSingleBaseProb;
	float[][] probMatrix;
	
	int noSambles;
	double [][] weightedBasePairProb;
	double beta = 10;
	double weightedSum = 0;
	double firstLikelihood = 0;
	double posteriorProbabilityAvg = 0;

	public PPFold() {
		screenable = true;
		outputable = true;
		postprocessable = true;
		postprocessWrite = true;
	}

	@Override
	public String getTabName() {
		return "PPFold";
	}

	@Override
	public Icon getIcon() {
		return new ImageIcon(ClassLoader.getSystemResource("icons/MPD.gif"));
	}

	@Override
	public JPanel getJPanel() {
		return pan;
	}

	@Override
	public String getTip() {
		return "Base-pairing matrix of the current consensus structure given by PPFold";
	}

	@Override
	public void setSampling(boolean enabled) {
		sampling = enabled;
	}

	@Override
	public String[] getDependences() {
		return new String[] { "statalign.postprocess.plugins.CurrentAlignment", "statalign.postprocess.plugins.MpdAlignment"};
	}

	@Override
	public void refToDependences(Postprocess[] plugins) {
		curAlig = (CurrentAlignment) plugins[0];
		mpdAlignment = (MpdAlignment) plugins[1];
	}

	static Comparator<String[]> compStringArr = new Comparator<String[]>() {
		public int compare(String[] a1, String[] a2) {
			return a1[0].compareTo(a2[0]);
		}
	};

	@Override
	public void beforeFirstSample(InputData input) {
		int maxLength = 0;
		title = input.title;
		// refSeq = input.seqs.sequences.get(0);
		refSeq = input.seqs.sequences.get(0).replaceAll("-", "");
		refSeqName = input.seqs.seqNames.get(0);
		refSeqGapped = input.seqs.sequences.get(0);
		maxLength = refSeq.length();
		for (int i = 0; i < input.seqs.sequences.size(); i++) {
			String seq = input.seqs.sequences.get(i).replaceAll("-", "");
			if (seq.length() > maxLength) {
				maxLength = seq.length();
				refSeq = seq;
				refSeqName = input.seqs.seqNames.get(i);
				refSeqGapped = input.seqs.sequences.get(i);
				seqNo = i;
			}
		}

		// System.out.println("using seq no. " + seqNo);

		d = refSeq.length();
		
		
		if(show) {
			pan.removeAll();
			title = input.title;
			JScrollPane scroll = new JScrollPane();
			scroll.getViewport().add(gui = new PPFoldGUI(title, scroll));
			
			if(gui.getPreferredSize().getHeight() > gui.getHeight()) {
				scroll.createVerticalScrollBar();
			}
			
			if(gui.getPreferredSize().getWidth() > gui.getWidth()) {
				scroll.createHorizontalScrollBar();
			}
			
			pan.add(scroll, BorderLayout.CENTER);
			pan.getParent().validate();
			
		}
		
		gui.changeDimension(d);
		sizeOfAlignments = (mcmc.tree.vertex.length + 1) / 2;
		noSambles = 0;

		t = new String[sizeOfAlignments][];
		sequences = null;
		viterbialignment = new String[sizeOfAlignments];

		network = new ColumnNetwork();

		firstDescriptor = new int[sizeOfAlignments];
		Arrays.fill(firstDescriptor, -1);
		firstVector = network.add(firstDescriptor);
		lastVector = null;

		viterbialignment = new String[sizeOfAlignments];
	}
	
	public double saveMPDToFile(String [] fastaAlignment, File outFile)
	{
		double ppfoldReliability = 0;
		List<String> lines = new ArrayList<String>();
		for (int i = 0; i < fastaAlignment.length; i++) {			
			lines.add(fastaAlignment[i]);
		}
		
		try
		{
			BufferedWriter buffer = new BufferedWriter(new FileWriter("mpd.fas"));
			for (int i = 0; i < fastaAlignment.length; i++) {
				buffer.write(fastaAlignment[i]+"\n");
			}
			buffer.close();
		}
		catch(IOException ex)
		{
			ex.printStackTrace();
		}

		
		try {
			
			Alignment align = AlignmentReader.readAlignmentFromStringList(lines);

			BufferedReader paramFileReader = null;
			Parameters param;
			File file = new File("res/matrices.in");
			paramFileReader = new BufferedReader(new InputStreamReader(
					new FileInputStream(file)));

			param = Parameters.readParam(paramFileReader);
			

			
			List<String> sequences = align.getSequences();
			List<String> seqNames = align.getNames();
			String refSeq = sequences.get(0).replaceAll("-", "");
			String refSeqName = seqNames.get(0);
			String refSeqGapped = sequences.get(0);
			int maxLength = refSeq.length();
			for (int i = 0; i < sequences.size(); i++) {
				String seq = sequences.get(i).replaceAll("-", "");
				if (seq.length() > maxLength) {
					maxLength = seq.length();
					refSeq = seq;
					refSeqName = seqNames.get(i);
					refSeqGapped = sequences.get(i);
				}
			}
			

			List<ExtraData> extradata = new ArrayList<ExtraData>();
			float [][] basePairProb = PPfoldMain.fold(progress, align.getSequences(), align.getNames(), null, param, extradata);			
			int [] pairedSites = RNAFoldingTools.getPosteriorDecodingConsensusStructure(basePairProb);
			ppfoldReliability = RNAFoldingTools.calculatePPfoldReliabilityScore(pairedSites, RNAFoldingTools.getDoubleMatrix(basePairProb));
			int [] projectedPairedSites = Benchmarks.projectPairedSites(refSeqGapped, pairedSites);
			System.out.println("PPfold reliability score (MPD) = " + ppfoldReliability);
			System.out.println("Improved reliability score 1 (MPD) = " + (posteriorProbabilityAvg*ppfoldReliability));
			double proxySimilarity = getProxySimilarityFromAvgPosterior(posteriorProbabilityAvg);
			System.out.println("Improved reliability score 2 (MPD) = " + (proxySimilarity*ppfoldReliability));
			
			
			try
			{
				BufferedWriter buffer = new BufferedWriter(new FileWriter(outFile));
				buffer.write(refSeqGapped+"\n");
				buffer.write(RNAFoldingTools.getDotBracketStringFromPairedSites(projectedPairedSites)+"\n");
				buffer.close();
			}
			catch(IOException ex)
			{
				ex.printStackTrace();
			}
			
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return ppfoldReliability;
	}

	@Override
	public void newSample(State state, int no, int total) {
		
		
		for (int i = 0; i < t.length; i++) {
			t[i] = curAlig.leafAlignment[i].split("\t");
		}
		Arrays.sort(t, compStringArr);

		int[] previousDescriptor = firstDescriptor;

		int i, j, len = t[0][1].length();
		for (j = 0; j < len; j++) {
			int[] nextDescriptor = new int[sizeOfAlignments];
			boolean allGap = true;
			for (int k = 0; k < sizeOfAlignments; k++) {
				if (t[k][1].charAt(j) == '-')
					nextDescriptor[k] = ColumnKey
							.colNext(previousDescriptor[k]);
				else {
					nextDescriptor[k] = ColumnKey
							.colNext(previousDescriptor[k]) + 1;
					allGap = false;
				}
			}
			if (!allGap)
				network.add(nextDescriptor);// [j]);

			previousDescriptor = nextDescriptor;
		}// j (length of alignments)

		if (no == 0) { // add last vector once only
			int[] lastDescriptor = new int[sizeOfAlignments];
			for (j = 0; j < sizeOfAlignments; j++) {
				lastDescriptor[j] = ColumnKey.colNext(previousDescriptor[j]) + 1;
			}
			lastVector = network.add(lastDescriptor);
		}
		if (no == 0 || 1 < 2) {
			network.updateViterbi(no + 1);
			// System.out.println("sequences first: "+sequences);

			if (sequences == null) {
				sequences = new String[sizeOfAlignments];
				for (i = 0; i < sizeOfAlignments; i++) {
					sequences[i] = "";
					for (j = 0; j < len; j++) {
						if (t[i][1].charAt(j) != '-') {
							sequences[i] += t[i][1].charAt(j);
						}
					}
				}
			}
			for (i = 0; i < sizeOfAlignments; i++)
				viterbialignment[i] = "";
			Column actualVector = lastVector.viterbi;
			ArrayList<Integer> posteriorList = new ArrayList<Integer>();
			while (!actualVector.equals(firstVector)) {
				int[] desc = actualVector.key.desc;
				posteriorList.add(new Integer(actualVector.count));
				for (i = 0; i < desc.length; i++) {
					if ((desc[i] & 1) == 0) {
						viterbialignment[i] = "-" + viterbialignment[i];
					} else {
						viterbialignment[i] = sequences[i].charAt(desc[i] >> 1)
								+ viterbialignment[i];
					}
				}
				actualVector = actualVector.viterbi;

			}

			if (no == 0) {
				summedArray = new float[d][d];
				weightedBasePairProb = new double[d][d];
				for (i = 0; i < d; ++i) {
					for (j = 0; j < d; ++j) {
						summedArray[i][j] = 0;
					}
				}
				summedSingleBaseProb = new float[d];
			}

			try {
				List<String> lines = new ArrayList<String>();
				for (i = 0; i < sequences.length; ++i) {
					System.out.println(">" + t[i][0]);
					lines.add(">" + t[i][0].trim());
					System.out.println(t[i][1]);
					lines.add(t[i][1]);
				}

				Alignment align = AlignmentReader
						.readAlignmentFromStringList(lines);

				// Tree tree = null;

				Tree tree = getPPfoldTree(mcmc);

				BufferedReader paramFileReader = null;
				Parameters param;
				File file = new File("res/matrices.in");
				paramFileReader = new BufferedReader(new InputStreamReader(
						new FileInputStream(file)));

				param = Parameters.readParam(paramFileReader);

				List<ExtraData> extradata = new ArrayList<ExtraData>();
				float[][] basePairProb = PPfoldMain.fold(progress, align.getSequences(),
						align.getNames(), tree, param, extradata);
				float[] singleBaseProb = new float[basePairProb.length];
				for (int x = 0; x < basePairProb.length; x++) {
					singleBaseProb[x] = 1;
					for (int y = 0; y < basePairProb[0].length; y++) {
						singleBaseProb[x] -= basePairProb[x][y];
					}
				}

				ArrayList<String> sequences = new ArrayList<String>();
				for (int k = 0; k < t.length; k++) {
					sequences.add(t[k][1]);
					// System.out.println(k+"\t"+t[k][1]);
				}

				// System.out.println(t[seqNo][1]);
				// System.out.println(refSeq);
				// String mapSeq =
				// RNAFoldingTools.getReferenceSequence(sequences,
				// refSeq.length());
				// System.out.println("REFSEQ: " + mapSeq);
				// float[][] projectFun = Mapping.projectMatrix(mapSeq, fun,
				// '-');
				float[][] projectFun = Mapping.projectMatrix(
						PPFold.getSequenceByName(t, refSeqName), basePairProb, '-');
				// float [] projectSingleBaseProb = Mapping.projectArray(mapSeq,
				// singleBaseProb, '-');
				float[] projectSingleBaseProb = Mapping.projectarray(
						PPFold.getSequenceByName(t, refSeqName),
						singleBaseProb, '-');
				
				// normalise projected matrix
				for(int x = 0 ; x < projectFun.length ; x++)
				{
					double rowMatrSum = 0;
					for(int y = 0 ; y < projectFun[0].length ; y++)
					{
						rowMatrSum += projectFun[x][y];
					}
					
					double factor = rowMatrSum + projectSingleBaseProb[x];
					System.out.println("F:"+factor);
					for(int y = 0 ; y < projectFun[0].length ; y++)
					{
						projectFun[x][y] = (float)(projectFun[x][y] / factor);
						projectSingleBaseProb[x] /= factor;
					}
				}
				
				double alignmentLogLikelihood = mcmc.mcmcStep.newLogLike;
				if(noSambles == 0)
				{
					firstLikelihood = mcmc.mcmcStep.newLogLike;
					weightedSum  = 0;
				}
				try
				{
					BufferedWriter buffer = new BufferedWriter(new FileWriter("likelihoods.txt", true));
					buffer.write(noSambles+"\t"+(alignmentLogLikelihood - firstLikelihood)+"\n");
					buffer.close();
					
					//System.out.println(noSambles+"\t"+mcmc.mcmcStep.newLogLike);
				}
				catch(IOException ex)
				{
					ex.printStackTrace();
				}
				
				RNAFoldingTools rnaTools = new RNAFoldingTools();
				boolean append = true;
				if(noSambles == 0)
				{
					append = false;
				}
				PPFold.appendFolds(new File(title+".folds"), noSambles+"", PPFold.getSequenceByName(t, refSeqName),rnaTools.getPosteriorDecodingConsensusStructureMultiThreaded(basePairProb), rnaTools.getPosteriorDecodingConsensusStructureMultiThreaded(projectFun), append);

				/*
				System.out.println("D=" + d);
				System.out.println(summedArray.length);
				System.out.println(projectFun.length);*/
				probMatrix = new float[d][d];

				double weight = Math.pow(firstLikelihood / alignmentLogLikelihood, beta);

				for (i = 0; i < d; ++i) {
					summedSingleBaseProb[i] += projectSingleBaseProb[i];
					for (j = 0; j < d; ++j) {
						summedArray[i][j] += projectFun[i][j];

						probMatrix[i][j] = summedArray[i][j]/(float)(noSambles+1);
						gui.setMatrix(probMatrix);
						gui.repaint();

						weightedBasePairProb[i][j] += projectFun[i][j]*weight;

					}
				}

				
				weightedSum += weight;
				try
				{
					BufferedWriter buffer = new BufferedWriter(new FileWriter("weights.txt", true));
					buffer.write(noSambles+"\t"+weightedSum+"\t"+weight+"\n");
					buffer.close();
					
					//System.out.println(noSambles+"\t"+mcmc.mcmcStep.newLogLike);
				}
				catch(IOException ex)
				{
					ex.printStackTrace();
				}

				noSambles += 1;
				
				//RNAFoldingTools rnaTools = new RNAFoldingTools();
				//String seq = this.getSequenceByName(t, this.refSeqName).replaceAll("-", "");
				//RNAFoldingTools rnaTools = new RNAFoldingTools();
				String seq = getSequenceByName(t, PPFold.refSeqName).replaceAll("-", "");
				int[] pairedSites = rnaTools.getPosteriorDecodingConsensusStructureMultiThreaded(probMatrix);
				System.out.println(RNAFoldingTools.getDotBracketStringFromPairedSites(pairedSites));
				
				Structure.updateMatrix(probMatrix);
				
				PPfoldMain.setfoldingfinished(true);
				
				
				/*float[][] probMatrix = new float[d][d];
				for(int x = 0; x < d; x++) {
					for(int y = 0; y < d; y++) {
						probMatrix[x][y] = summedArray[x][y]/noSambles;
					}
				}*/
				//if(sampling) {
				
				gui.changeDimension(d*PPFoldGUI.OFFSET);
				gui.setMatrix(probMatrix);
				gui.repaint();
				//}

				/*
				 * for(i = 0; i<matrix.length; ++i){ for(j = 0; j<matrix.length;
				 * ++j){ System.out.print(matrix[i][j]); } System.out.println();
				 * }
				 */

			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
		// save alignment sample information
		if(no == 0)
		{
			String [] [] inputAlignment = new String[mpdAlignment.input.seqs.sequences.size()][2];
			for(int k = 0 ; k < inputAlignment.length ; k++)
			{
				inputAlignment[k][0] = mpdAlignment.input.seqs.seqNames.get(k);
				inputAlignment[k][1] = mpdAlignment.input.seqs.sequences.get(k);
			}
			Arrays.sort(inputAlignment, compStringArr);
			
			appendAlignment("reference", inputAlignment, new File(mpdAlignment.input.title+".samples"), false);
			appendAlignment(no+"", t, new File(mpdAlignment.input.title+".samples"), true);
		}
		else
		{
			appendAlignment(no+"", t, new File(mpdAlignment.input.title+".samples"), true);
		}
	}

	@Override
	public void afterLastSample() {
		double[][] doubleSummedArray = new double[d][d];
		double[] doubleSingleBaseProb = new double[d];
		for (int i = 0; i < d; ++i) {
			doubleSingleBaseProb[i] = summedSingleBaseProb[i]
					/ (double) noSambles;
			for (int j = 0; j < d; ++j) {
				doubleSummedArray[i][j] = (double) summedArray[i][j]
						/ (double) noSambles;
			}
		}

		RNAFoldingTools rnaTools = new RNAFoldingTools();

		int[] pairedSites = rnaTools
				.getPosteriorDecodingConsensusStructureMultiThreaded(doubleSummedArray);
		
		RNAFoldingTools.writeMatrix(RNAFoldingTools.getDoubleMatrix(probMatrix), new File("prob.matrix"));
		
		
		RNAFoldingTools.writeMatrix(doubleSummedArray, new File("bp.matrix"));
		System.out.println("num samples" + noSambles);
		//RNAFoldingTools.writeMatrix(summedArray, new File("bp2.matrix"));
		double[] singleBaseProb = RNAFoldingTools
				.getSingleBaseProb(doubleSummedArray);
		saveResult(refSeqGapped, pairedSites, doubleSummedArray,
				singleBaseProb, new File(title+".dat.res"));
		
		//System.out.printl
		for (int i = 0; i < d; ++i) {
			for (int j = 0; j < d; ++j) {
				this.weightedBasePairProb[i][j] /= weightedSum;
			}
			// System.out.println();
		}
		RNAFoldingTools.writeMatrix(weightedBasePairProb, new File("bp_log.matrix"));
		saveResult(refSeqGapped, rnaTools.getPosteriorDecodingConsensusStructureMultiThreaded(weightedBasePairProb), weightedBasePairProb, RNAFoldingTools.getSingleBaseProb(weightedBasePairProb), new File(title+".dat.res.weighted"));
		
	
		try
		{

			BufferedWriter buffer = new BufferedWriter(new FileWriter(new File(mpdAlignment.input.title+".samples"), true));
			buffer.write("%posteriors\n");
			for(int i = 0 ; i < mpdAlignment.decoding.length ; i++)
			{
				buffer.write(mpdAlignment.decoding[i]+"\n");
			}
			buffer.close();
		}
		catch(IOException ex)
		{
			ex.printStackTrace();
		}
		
		// calculate "distance from reference"
		posteriorProbabilityAvg = 0;
		for(int i = 0 ; i < mpdAlignment.decoding.length ; i++)
		{
			posteriorProbabilityAvg += mpdAlignment.decoding[i];
		}
		posteriorProbabilityAvg /= (double)(mpdAlignment.decoding.length);
		System.out.println("Posterior probability avg: " + posteriorProbabilityAvg);
		
		double ppfoldReliablityScore = RNAFoldingTools.calculatePPfoldReliabilityScore(pairedSites, doubleSummedArray);
		System.out.println("Structure reliability score = " + ppfoldReliablityScore);
		double proxySim = getProxySimilarityFromAvgPosterior(posteriorProbabilityAvg);
		System.out.println("Proxy similiarity = " + proxySim);
		ArrayList<String> sequences = new ArrayList<String>();
		for(int i = 0 ; i < mpdAlignment.alignment.length ; i++)
		{
			sequences.add(mpdAlignment.alignment[i]);
		}			
		System.out.println("Proxy distance = " + Distance.amaScoreToMultiDistance(sequences,proxySim));
		double improvedReliabilityScore1 = posteriorProbabilityAvg*ppfoldReliablityScore;		
		System.out.println("Improved reliability score 1 = " + improvedReliabilityScore1);
		double improvedReliabilityScore2 = proxySim*ppfoldReliablityScore;		
		System.out.println("Improved reliability score 2 = " + improvedReliabilityScore2);
		
		// save mpd alignment
		appendAlignment("mpd", mpdAlignment.alignment, new File(mpdAlignment.input.title+".samples"), true, mpdAlignment.input);
		double ppfoldReliabilityScoreMPD = saveMPDToFile(Utils.alignmentTransformation(mpdAlignment.alignment,
				"Fasta", mpdAlignment.input), new File(mpdAlignment.input.title+".dat.res.mpd"));
		
		this.saveScores(new File("scores.txt"), ppfoldReliablityScore, ppfoldReliabilityScoreMPD);
	}
	
	public void saveScores(File outFile, double ppfoldReliabilityStatAlign, double ppfoldReliabilityMPD)
	{
		boolean writeHeaders = !outFile.exists();
		try
		{
			BufferedWriter buffer = new BufferedWriter(new FileWriter(outFile, !writeHeaders));
			if(writeHeaders)
			{
				buffer.write("Dataset\tPosterior avg.\tProxy similarity\tProxy distance\tPPfold reliability (StatAlign)\tPPfold reliability(MPD)\tBurn-in\tCycles\tSample rate\n");
			}
			
			double proxySimilarity = getProxySimilarityFromAvgPosterior(posteriorProbabilityAvg);
			ArrayList<String> sequences = new ArrayList<String>();
			for(int i = 0 ; i < mpdAlignment.alignment.length ; i++)
			{
				sequences.add(mpdAlignment.alignment[i]);
			}
			double proxyDistance = Distance.amaScoreToMultiDistance(sequences,proxySimilarity);
			buffer.write(mpdAlignment.input.title+"\t");
			buffer.write(posteriorProbabilityAvg+"\t");
			buffer.write(proxySimilarity+"\t");
			buffer.write(proxyDistance+"\t");
			buffer.write(ppfoldReliabilityStatAlign+"\t");
			buffer.write(ppfoldReliabilityMPD+"\t");
			buffer.write(mcmc.mcmcpars.burnIn+"\t");
			buffer.write(mcmc.mcmcpars.cycles+"\t");
			buffer.write(mcmc.mcmcpars.sampRate+"\t");
			buffer.write("\n");
			buffer.close();
		}
		catch(IOException ex)
		{
			ex.printStackTrace();
		}
	}
	
	public static double getProxySimilarityFromAvgPosterior(double avgPosterior)
	{
		return Math.min(1, avgPosterior*0.8084689957 + 0.2011076631);
	}
	

	
	public static void appendAlignment(String label, String [][] alignment, File outFile, boolean append)
	{
		try
		{
			BufferedWriter buffer = new BufferedWriter(new FileWriter(outFile, append));
			buffer.write("%"+label+"\n");
			for (int i = 0; i < alignment.length; i++) {
				buffer.write(">"+alignment[i][0] + "\n");
				buffer.write(alignment[i][1] + "\n");
			}
			buffer.close();
		}
		catch(IOException ex)
		{
			ex.printStackTrace();
		}
	}
	
	public static void appendAlignment(String label, String [] alignment, File outFile, boolean append, InputData input)
	{
		try
		{
			BufferedWriter buffer = new BufferedWriter(new FileWriter(outFile, append));
			buffer.write("%"+label+"\n");
			String [] fastaAlignment = Utils.alignmentTransformation(alignment,"Fasta", input);
			for (int i = 0; i < fastaAlignment.length; i++) {
				buffer.write(fastaAlignment[i] + "\n");
			}
			buffer.close();
		}
		catch(IOException ex)
		{
			ex.printStackTrace();
		}
	}

	public static String getSequenceByName(String[][] sequences, String name) {
		for (int i = 0; i < sequences.length; i++) {
			System.out.println(sequences[i]+"\t"+name);
			if (sequences[i] != null && sequences[i][0].equals(name)) {
				return sequences[i][1];
			}
		}
		return null;
	}

	public static void saveResult(String sequence, int[] pairedSites,
			double[][] basePairProb, double[] singleBaseProb, File outFile) {
		DecimalFormat df = new DecimalFormat("0.0000");
		try {
			BufferedWriter buffer = new BufferedWriter(new FileWriter(outFile));
			buffer.write(sequence + "\n");
			buffer.write(RNAFoldingTools
					.getDotBracketStringFromPairedSites(pairedSites) + "\n");
			for (int k = 0; k < pairedSites.length; k++) {
				if (pairedSites[k] == 0) {
					buffer.write(RNAFoldingTools.pad((k + 1) + "", 4)
							+ "\t"
							+ RNAFoldingTools.pad(pairedSites[k] + "", 7)
							+ RNAFoldingTools.pad("-", 6)
							+ "\t"
							+ RNAFoldingTools.pad(df.format(singleBaseProb[k])
									+ "", 6) + "\n");
				} else {
					buffer.write(RNAFoldingTools.pad((k + 1) + "", 4)
							+ "\t"
							+ RNAFoldingTools.pad(pairedSites[k] + "", 7)
							+ RNAFoldingTools.pad(
									df.format(basePairProb[k][pairedSites[k] - 1]),
									6)
							+ "\t"
							+ RNAFoldingTools.pad(df.format(singleBaseProb[k])
									+ "", 6) + "\n");
				}
			}

			buffer.close();
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}

	public static com.ppfold.algo.Tree getPPfoldTree(Mcmc mcmc) {
		try {
			return NewickReader.parse(mcmc.tree.printedTree());
		} catch (Exception ex) {
			ex.printStackTrace();
		}

		return null;
	}
	
	public static String[][] getSequences() {
		return t;
	}
	
	public static String getRefName() {
		return refSeqName;
	}
	
	public static void appendFolds(File file, String name, String alignedSequence, int [] pairedSites, int [] projectedPairedSites, boolean append)
	{
		
		try {
			BufferedWriter buffer = new BufferedWriter(new FileWriter(file, append));
			buffer.write(">"+name+"\n");
			buffer.write(alignedSequence+"\n");
			buffer.write(RNAFoldingTools.getDotBracketStringFromPairedSites(pairedSites)+"\n");
			buffer.write(alignedSequence.replaceAll("-", "")+"\n");
			buffer.write(RNAFoldingTools.getDotBracketStringFromPairedSites(projectedPairedSites)+"\n");
			buffer.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public static ArrayList<String> loadFolds(File file, int line)
	{
		ArrayList<String> list = new ArrayList<String>();
		try
		{
			BufferedReader buffer = new BufferedReader(new FileReader(file));
			String textline = null;
			int lines = 0;
			while((textline = buffer.readLine()) != null)
			{
				if((lines - line) % 5 == 0)
				{
					list.add(textline);
				}
				
				lines++;
			}
			buffer.close();
		}
		catch(IOException ex)
		{
			ex.printStackTrace();
		}
		return list;
	}
}