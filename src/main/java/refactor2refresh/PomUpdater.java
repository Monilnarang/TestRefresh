package refactor2refresh;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class PomUpdater {

    private static final String TEST_CLASS_SUFFIX = "_Purified";
    private static final String JUNIT_JUPITER_PARAMS_GROUP_ID = "org.junit.jupiter";
    private static final String JUNIT_JUPITER_PARAMS_ARTIFACT_ID = "junit-jupiter-params";
    private static final String JUNIT_JUPITER_PARAMS_VERSION = "5.10.2";

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java PomUpdater <repository-path>");
            System.exit(1);
        }

        String repoPath = args[0];
        File repoDir = new File(repoPath);

        if (!repoDir.exists() || !repoDir.isDirectory()) {
            System.out.println("Error: The specified path does not exist or is not a directory.");
            System.exit(1);
        }

        try {
            // Find all Java test files with "_Purified" suffix
            List<File> purifiedTestFiles = findPurifiedTestFiles(repoDir);
            System.out.println("Found " + purifiedTestFiles.size() + " purified test files.");

            // Process each test file
            for (File testFile : purifiedTestFiles) {
                File pomFile = findPomFile(testFile);
                if (pomFile != null) {
                    updatePomFile(pomFile);
                } else {
                    System.out.println("Could not find pom.xml for: " + testFile.getAbsolutePath());
                }
            }

            System.out.println("Process completed successfully.");

        } catch (Exception e) {
            System.err.println("An error occurred: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Find all Java test files with "_Purified" suffix in the repository.
     */
    private static List<File> findPurifiedTestFiles(File dir) throws IOException {
        List<File> result = new ArrayList<>();

        // Use Java NIO to walk the directory tree
        List<Path> paths = Files.walk(Paths.get(dir.getAbsolutePath()))
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .collect(Collectors.toList());

        // Check if files match the naming pattern
        for (Path path : paths) {
            File file = path.toFile();
            String fileName = file.getName();
            if (fileName.endsWith(TEST_CLASS_SUFFIX + ".java")) {
                // Verify it's a test class by checking content for @Test annotations
                String content = new String(Files.readAllBytes(path));
                if (content.contains("@Test") ||
                        content.contains("extends TestCase") ||
                        content.contains("import org.junit.")) {
                    result.add(file);
                    System.out.println("Found purified test file: " + file.getAbsolutePath());
                }
            }
        }

        return result;
    }

    /**
     * Find the nearest pom.xml file by traversing up the directory structure.
     */
    private static File findPomFile(File testFile) {
        File currentDir = testFile.getParentFile();

        while (currentDir != null) {
            File pomFile = new File(currentDir, "pom.xml");
            if (pomFile.exists() && pomFile.isFile()) {
                System.out.println("Found pom.xml for " + testFile.getName() + " at: " + pomFile.getAbsolutePath());
                return pomFile;
            }
            currentDir = currentDir.getParentFile();
        }

        return null;
    }

    /**
     * Update pom.xml to include JUnit Jupiter Params dependency if it doesn't exist.
     */
    private static void updatePomFile(File pomFile)
            throws ParserConfigurationException, SAXException, IOException, TransformerException {

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(pomFile);
        doc.getDocumentElement().normalize();

        // Check if dependency already exists
        if (!hasJupiterParamsDependency(doc)) {
            addJupiterParamsDependency(doc);

            // Write the changes back to the pom.xml file
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

            DOMSource source = new DOMSource(doc);
            StreamResult result = new StreamResult(pomFile);
            transformer.transform(source, result);

            System.out.println("Updated pom.xml at: " + pomFile.getAbsolutePath());
        } else {
            System.out.println("JUnit Jupiter Params dependency already exists in: " + pomFile.getAbsolutePath());
        }
    }

    /**
     * Check if pom.xml already has JUnit Jupiter Params dependency.
     */
    private static boolean hasJupiterParamsDependency(Document doc) {
        NodeList dependencies = doc.getElementsByTagName("dependency");

        for (int i = 0; i < dependencies.getLength(); i++) {
            Node dependency = dependencies.item(i);

            if (dependency.getNodeType() == Node.ELEMENT_NODE) {
                Element element = (Element) dependency;

                NodeList groupIdNodes = element.getElementsByTagName("groupId");
                NodeList artifactIdNodes = element.getElementsByTagName("artifactId");

                if (groupIdNodes.getLength() > 0 && artifactIdNodes.getLength() > 0) {
                    String groupId = groupIdNodes.item(0).getTextContent();
                    String artifactId = artifactIdNodes.item(0).getTextContent();

                    if (JUNIT_JUPITER_PARAMS_GROUP_ID.equals(groupId) &&
                            JUNIT_JUPITER_PARAMS_ARTIFACT_ID.equals(artifactId)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Add JUnit Jupiter Params dependency to pom.xml.
     */
    private static void addJupiterParamsDependency(Document doc) {
        // Find or create dependencies element
        NodeList dependenciesNodes = doc.getElementsByTagName("dependencies");
        Element dependencies;

        if (dependenciesNodes.getLength() > 0) {
            dependencies = (Element) dependenciesNodes.item(0);
        } else {
            // Create dependencies element if it doesn't exist
            dependencies = doc.createElement("dependencies");

            // Find project element and add dependencies to it
            Element project = doc.getDocumentElement();
            project.appendChild(dependencies);
        }

        // Create dependency element
        Element dependency = doc.createElement("dependency");

        // Create and add groupId element
        Element groupId = doc.createElement("groupId");
        groupId.setTextContent(JUNIT_JUPITER_PARAMS_GROUP_ID);
        dependency.appendChild(groupId);

        // Create and add artifactId element
        Element artifactId = doc.createElement("artifactId");
        artifactId.setTextContent(JUNIT_JUPITER_PARAMS_ARTIFACT_ID);
        dependency.appendChild(artifactId);

        // Create and add version element
        Element version = doc.createElement("version");
        version.setTextContent(JUNIT_JUPITER_PARAMS_VERSION);
        dependency.appendChild(version);

        // Add dependency to dependencies
        dependencies.appendChild(dependency);
    }
}