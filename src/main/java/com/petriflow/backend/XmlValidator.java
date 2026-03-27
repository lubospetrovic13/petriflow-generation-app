package com.petriflow.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates generated Petriflow XML against:
 * 1. XSD schema (petriflow.schema.xsd)
 * 2. Six deterministic "gotcha" patterns from petriflow_reference.md
 */
public class XmlValidator {

    private static final Logger log = LoggerFactory.getLogger(XmlValidator.class);

    public static class ValidationResult {
        public final List<String> errors;
        public final boolean isValid;

        public ValidationResult(List<String> errors) {
            this.errors = errors;
            this.isValid = errors.isEmpty();
        }
    }

    /**
     * Validates XML against XSD + gotcha checks.
     * Returns ValidationResult with error list.
     */
    public static ValidationResult validate(String xml) {
        List<String> errors = new ArrayList<>();

        // Skip validation if XML is empty or not XML
        if (xml == null || xml.trim().isEmpty() || !xml.trim().startsWith("<")) {
            return new ValidationResult(errors); // No errors, not XML content
        }

        // 1. XSD validation
        errors.addAll(validateAgainstXsd(xml));

        // 2. Gotcha pattern checks
        errors.addAll(checkGotchaPatterns(xml));

        return new ValidationResult(errors);
    }

    /**
     * Validates XML against petriflow.schema.xsd
     */
    private static List<String> validateAgainstXsd(String xml) {
        List<String> errors = new ArrayList<>();
        try {
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            InputStream xsdStream = XmlValidator.class.getClassLoader()
                    .getResourceAsStream("petriflow.schema.xsd");

            if (xsdStream == null) {
                log.warn("petriflow.schema.xsd not found in classpath, skipping XSD validation");
                return errors;
            }

            Schema schema = factory.newSchema(new StreamSource(xsdStream));
            Validator validator = schema.newValidator();

            ByteArrayInputStream xmlStream = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
            validator.validate(new StreamSource(xmlStream));

        } catch (SAXException e) {
            errors.add("XSD validation error: " + e.getMessage());
        } catch (Exception e) {
            log.error("XSD validation failed: {}", e.getMessage());
            errors.add("XSD validation failed: " + e.getMessage());
        }
        return errors;
    }

    /**
     * Checks 6 deterministic gotcha patterns from petriflow_reference.md
     */
    private static List<String> checkGotchaPatterns(String xml) {
        List<String> errors = new ArrayList<>();

        // 1. Check for type="textarea" (should be type="text" with component)
        if (xml.contains("type=\"textarea\"")) {
            errors.add("Found type=\"textarea\" - use type=\"text\" with <component><name>textarea</name></component> instead");
        }

        // 2. Check for <n> inside <component> (should be <name>)
        errors.addAll(checkInvalidComponentTag(xml));

        // 3. Check for unescaped & outside CDATA
        errors.addAll(checkUnescapedAmpersands(xml));

        // 4. Check for empty <caseEvents> block
        errors.addAll(checkEmptyCaseEvents(xml));

        // 5. Check for <role>default</role> or <role>anonymous</role>
        errors.addAll(checkRoleElements(xml));

        // 6. Check for self-reference in taskRef <init>
        errors.addAll(checkTaskRefSelfReference(xml));

        // 7. Check for Place→Place arcs (C2 violation)
        errors.addAll(checkPlaceToPlaceArcs(xml));

        // 8. Check for assignTask/finishTask on human tasks (C3 violation)
        errors.addAll(checkAssignTaskOnHumanTasks(xml));

        return errors;
    }

    /**
     * Check for <n> tag inside <component> block
     */
    private static List<String> checkInvalidComponentTag(String xml) {
        List<String> errors = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            ByteArrayInputStream input = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
            Document doc = builder.parse(input);

            NodeList components = doc.getElementsByTagName("component");
            for (int i = 0; i < components.getLength(); i++) {
                Element component = (Element) components.item(i);
                NodeList nTags = component.getElementsByTagName("n");
                if (nTags.getLength() > 0) {
                    errors.add("Found <n> tag inside <component> - should be <name> instead");
                    break;
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse XML for <n> check: {}", e.getMessage());
        }
        return errors;
    }

    /**
     * Check for unescaped & outside CDATA blocks
     */
    private static List<String> checkUnescapedAmpersands(String xml) {
        List<String> errors = new ArrayList<>();

        // Remove CDATA sections temporarily
        String xmlWithoutCdata = xml.replaceAll("<!\\[CDATA\\[.*?\\]\\]>", "");

        // Pattern: & not followed by valid entity reference
        Pattern pattern = Pattern.compile("&(?!(amp|lt|gt|quot|apos);)");
        Matcher matcher = pattern.matcher(xmlWithoutCdata);

        if (matcher.find()) {
            errors.add("Found unescaped '&' character - use &amp; instead (in labels, titles, placeholders, or options)");
        }

        return errors;
    }

    /**
     * Check for empty <caseEvents> block (no real event children)
     */
    private static List<String> checkEmptyCaseEvents(String xml) {
        List<String> errors = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            ByteArrayInputStream input = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
            Document doc = builder.parse(input);

            NodeList caseEvents = doc.getElementsByTagName("caseEvents");
            for (int i = 0; i < caseEvents.getLength(); i++) {
                Element caseEvent = (Element) caseEvents.item(i);
                NodeList events = caseEvent.getElementsByTagName("event");
                if (events.getLength() == 0) {
                    errors.add("Found empty <caseEvents> block - remove it or add actual <event> elements");
                    break;
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse XML for empty caseEvents check: {}", e.getMessage());
        }
        return errors;
    }

    /**
     * Check for <role>default</role> or <role>anonymous</role> as element
     */
    private static List<String> checkRoleElements(String xml) {
        List<String> errors = new ArrayList<>();

        if (xml.contains("<role>default</role>")) {
            errors.add("Found <role>default</role> - 'default' should never be declared as a role element");
        }
        if (xml.contains("<role>anonymous</role>")) {
            errors.add("Found <role>anonymous</role> - 'anonymous' should never be declared as a role element");
        }

        return errors;
    }

    /**
     * Check for self-reference in taskRef fields: <init>X</init> where X equals the field's own <id>
     */
    private static List<String> checkTaskRefSelfReference(String xml) {
        List<String> errors = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            ByteArrayInputStream input = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
            Document doc = builder.parse(input);

            NodeList dataNodes = doc.getElementsByTagName("data");
            for (int i = 0; i < dataNodes.getLength(); i++) {
                Element data = (Element) dataNodes.item(i);
                String type = data.getAttribute("type");

                if ("taskRef".equals(type)) {
                    String fieldId = getElementText(data, "id");
                    String initValue = getElementText(data, "init");

                    if (fieldId != null && initValue != null && fieldId.equals(initValue)) {
                        errors.add("Found taskRef field '" + fieldId + "' with self-reference in <init> - cannot initialize a taskRef with its own ID");
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse XML for taskRef self-reference check: {}", e.getMessage());
        }
        return errors;
    }

    /**
     * Helper to get text content of first child element with given tag name
     */
    private static String getElementText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return null;
    }

    /**
     * Check for Place→Place arcs (C2 violation).
     * Collects all place IDs, then checks if any arc goes from a place directly to another place.
     */
    private static List<String> checkPlaceToPlaceArcs(String xml) {
        List<String> errors = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            // Collect all place IDs
            java.util.Set<String> placeIds = new java.util.HashSet<>();
            NodeList places = doc.getElementsByTagName("place");
            for (int i = 0; i < places.getLength(); i++) {
                String id = getElementText((Element) places.item(i), "id");
                if (id != null) placeIds.add(id);
            }

            // Check arcs: if both sourceId and destinationId are place IDs → P→P violation
            NodeList arcs = doc.getElementsByTagName("arc");
            for (int i = 0; i < arcs.getLength(); i++) {
                Element arc = (Element) arcs.item(i);
                String src = getElementText(arc, "sourceId");
                String dst = getElementText(arc, "destinationId");
                if (src != null && dst != null && placeIds.contains(src) && placeIds.contains(dst)) {
                    errors.add("Place→Place arc detected: " + src + " → " + dst +
                            " (C2 violation: arcs must alternate Place→Transition→Place)");
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse XML for P→P arc check: {}", e.getMessage());
        }
        return errors;
    }

    /**
     * Check for assignTask/finishTask called on human tasks (C3 violation).
     * Collects system-role transition IDs, then checks if any CDATA action calls
     * assignTask/finishTask with a transition ID that is NOT a system task.
     */
    private static List<String> checkAssignTaskOnHumanTasks(String xml) {
        List<String> errors = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            // Collect system-role transition IDs
            java.util.Set<String> systemTransitions = new java.util.HashSet<>();
            NodeList transitions = doc.getElementsByTagName("transition");
            for (int i = 0; i < transitions.getLength(); i++) {
                Element t = (Element) transitions.item(i);
                String tid = getElementText(t, "id");
                NodeList roleRefs = t.getElementsByTagName("roleRef");
                for (int j = 0; j < roleRefs.getLength(); j++) {
                    Element rr = (Element) roleRefs.item(j);
                    String roleId = getElementText(rr, "id");
                    if ("system".equals(roleId)) {
                        systemTransitions.add(tid);
                        break;
                    }
                }
            }

            // Scan CDATA blocks for assignTask("X") / finishTask("X") where X is not a system task
            Pattern pattern = Pattern.compile("(?:assignTask|finishTask)\\(\"([^\"]+)\"\\)");
            Matcher matcher = pattern.matcher(xml);
            java.util.Set<String> reported = new java.util.HashSet<>();
            while (matcher.find()) {
                String targetId = matcher.group(1);
                if (!systemTransitions.contains(targetId) && !reported.contains(targetId)) {
                    errors.add("assignTask/finishTask called on non-system transition '" + targetId +
                            "' (C3 violation: only system-role transitions should be bootstrapped this way)");
                    reported.add(targetId);
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse XML for assignTask-on-human-task check: {}", e.getMessage());
        }
        return errors;
    }

}