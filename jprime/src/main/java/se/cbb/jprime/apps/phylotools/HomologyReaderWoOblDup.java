package se.cbb.jprime.apps.phylotools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import se.cbb.jprime.io.NewickIOException;
import se.cbb.jprime.io.NewickVertex;
import se.cbb.jprime.io.PrIMENewickTree;
import se.cbb.jprime.io.PrIMENewickTreeReader;

/**
 * First script used for (i) Testing whether I can code in JPrIME, and (ii)
 * reading homology event in true reconciliation file generated by
 * prime_generateTree of PrIME C++ version PS. This code needs refactoring and
 * clean-up. To be done soon.
 * 
 * @author Ikram Ullah
 */

public class HomologyReaderWoOblDup {
    ArrayList<Integer> mprSpeciationNodes;
    ArrayList<String> mprSpeciationLeaves;

    public static void main(String[] args) {
        HomologyReaderWoOblDup hrwd = new HomologyReaderWoOblDup();
        try {
            hrwd.init(args);
        } catch (IOException ioe) {
            System.err.println("IO Exception: " + ioe.getMessage());
        } catch (NewickIOException ioe) {
            System.err.println("NewickIO Exception: " + ioe.getMessage());
        }
    }

    public HomologyReaderWoOblDup() {
        mprSpeciationNodes = new ArrayList<Integer>();
        mprSpeciationLeaves = new ArrayList<String>();
    }

    public void init(String[] args) throws IOException, NewickIOException {
        if (args.length != 2) {
            System.err.println("Usage: java -classpath jprime.jar se.cbb.jprime.apps.phylotools.GeneTreeHomologyReader gene_tree_name specie_tree_name");
            System.out.println("Exiting now...");
            System.exit(1);
        }

        // compute non-obligate vertices via MPR
        // String mprPath = "treeFromMPR.tree";
        findNonObligateVertices(args[0], args[1]);
        System.out.println("Input true reconciliation file is " + args[0]);
        String treepath = "treeFromReconFile.tree";
        parseTreeFromReconciliationFile(args[0], treepath);
        File gFile = new File(treepath);
        BufferedWriter writer = new BufferedWriter(new FileWriter(args[0]
                + ".original"));

        PrIMENewickTree sRaw = PrIMENewickTreeReader.readTree(gFile, false,
                true);
        // System.out.println("Tree is " + sRaw.toString());
        // System.out.println(sRaw.toString());
        System.out.println("Extracting true reconciliation events...");
        System.out.println("The tree is : " + sRaw);
        List<NewickVertex> vertices = sRaw.getVerticesAsList();
        int[] dupStatus = sRaw.getDuplicationValues();

        for (NewickVertex v : vertices) {
            int id = v.getNumber();
            // if(dupStatus[id] != Integer.MAX_VALUE){
            //if (this.mprSpeciationNodes.contains(id)) {
            if(isSpeciationNodeByMPR(v)) {
                ArrayList<NewickVertex> children = v.getChildren();
                String lchild = getLeafNames(children.get(0));
                String rchild = getLeafNames(children.get(1));
                if (!isObligateDuplication(lchild + " " + rchild)) {
                    // String lchild = getLeafIds(children.get(0));
                    // String rchild = getLeafIds(children.get(1));
                    // System.out.println("The childern ids are " + lchild +
                    // " and " + rchild);
                    // System.out.println("The node number " + id +
                    // " has dupStatus = " + dupStatus[id]);
                    writer.write("[" + lchild + ", " + rchild + "]" + "\t"
                            + dupStatus[id] + "\n");
                    System.out.println("[" + lchild + ", " + rchild + "]"
                            + "\t" + dupStatus[id]);
                }
            }
        }
        writer.flush();
        writer.close();
        gFile.deleteOnExit();
        System.out.println("Done...");
        System.out.println("True values has been written to " + args[0]
                + ".original");
    }

    private boolean isSpeciationNodeByMPR(NewickVertex v) {
        String leaves = getLeafNames(v);
        if(this.mprSpeciationLeaves.contains(leaves))
            return true;
        return false;
    }

    private void findNonObligateVertices(String trueFile, String specieTree)
            throws IOException, NewickIOException {    	
        String dirPath = trueFile.substring(0, trueFile.lastIndexOf('/')) + "/";
        String inputTree = trueFile.substring(trueFile.lastIndexOf('/')+1);
        String commonPrefix = inputTree.substring(0, inputTree.lastIndexOf('.'));
        String geneTree = dirPath + commonPrefix + ".tree";        
        //String specieTree = dirPath + "abca.stree";
        //String specieTree = spFile.substring(spFile.lastIndexOf('/')+1);
        String gsFile = specieTree + "_" + inputTree.substring(0,inputTree.lastIndexOf('.')) + ".tree.gs";
        String mprOutFile = dirPath + commonPrefix + ".mprvertices";
        String command = "mprOrthology " + geneTree + " ";
        command += specieTree + " " + gsFile + " " + mprOutFile;
        String waitMessage = "Please wait while MPR computations are done...";
        
        File mprf = new File(mprOutFile);
        if(mprf.exists())
        	mprf.delete();
        
        executeProgram("", command, waitMessage);

        File gFile = new File(mprOutFile);
        PrIMENewickTree sMpr = PrIMENewickTreeReader.readTree(gFile, false,
                true);
        System.out.println("Input tree is " + sMpr.toString());
        int[] mprDupStatus = sMpr.getDuplicationValues();
        List<NewickVertex> vertices = sMpr.getVerticesAsList();
        for (NewickVertex v : vertices) {
            int id = v.getNumber();
            if (mprDupStatus[id] == 1) {
                this.mprSpeciationNodes.add(id);
                this.mprSpeciationLeaves.add(getLeafNames(v));
            }
        }
    }

    private boolean isObligateDuplication(String list) {
        String[] childern = list.split(" ");
        String firstLeafName = (childern[0].split("_"))[1];
        // System.out.println(firstLeafName);
        for (int i = 1; i < childern.length; i++) {
            String nextLeafName = (childern[i].split("_"))[1];
            if (!nextLeafName.equalsIgnoreCase(firstLeafName))
                return false;
        }
        return true;
    }

    private void parseTreeFromReconciliationFile(String fileName,
            String gfileName) {
        try {
            // String gfileName = "treeFromReconFile.tree";
            File f = new File(gfileName);
            if (f.exists())
                f.delete();
            BufferedReader buf = new BufferedReader(new FileReader(fileName));
            BufferedWriter bw = new BufferedWriter(new FileWriter(gfileName));
            String line = "";
            while ((line = buf.readLine()) != null) {
                line = line.trim();
                if (line.charAt(0) == '#')
                    continue;
                else {
                    // StringTokenizer stk = new StringTokenizer(line);
                    // String tree = "";
                    //
                    // // true reconciliations is the last token
                    // while(stk.hasMoreTokens())
                    // tree = stk.nextToken();
                    String[] token = line.split(";");

                    String trueFile = "";
                    if (token.length == 1)
                        trueFile = token[0].trim();
                    else
                        trueFile = token[token.length-1].trim();
                    bw.write(trueFile);
                    bw.flush();                    
                    // return gfileName;
                }
            }
            bw.close();
            buf.close();
        } catch (Exception ex) {
            System.err.println("Error in reading reconciliation file");
            System.err.println("Reason: " + ex.getMessage());
        }
        // return null;
    }

    public String getLeafNames(NewickVertex vertex) {
        String lNames = getLeafNamesRecursive(vertex);
        return lNames;
    }

    private String getLeafNamesRecursive(NewickVertex vertex) {
        // TODO Auto-generated method stub
        if (vertex.isLeaf())
            return vertex.getName();
        else {
            ArrayList<NewickVertex> ch = vertex.getChildren();
            return getLeafNamesRecursive(ch.get(0)) + " "
                    + getLeafNamesRecursive(ch.get(1));
        }
    }

    public String getLeafIds(NewickVertex vertex) {
        String lNames = "";
        lNames += getLeafIdsRecursive(vertex);
        return lNames + "";
    }

    private String getLeafIdsRecursive(NewickVertex vertex) {
        if (vertex.isLeaf())
            return vertex.getNumber() + "";
        else {
            ArrayList<NewickVertex> ch = vertex.getChildren();
            return getLeafIdsRecursive(ch.get(0)) + " "
                    + getLeafIdsRecursive(ch.get(1));
        }
    }

    public void executeProgram(String scriptPath, String path,
            String waitMessage) {
        try {
            System.out.println(waitMessage);
            // ExecComman ecom = new ExecCommand(scriptPath + path, "STDIN");
            ExecCommand ecom = new ExecCommand(scriptPath + path, "log.out");
            System.out.println("Completed...");
        } catch (Exception e) {
            System.err.println("Could not execute the file " + path);
            System.err.println("Possible Error: " + e.getMessage());
        }
    }
}