package com.buukle.agent.common.skill;

import com.buukle.agent.common.sub.agent.InvalidSubRunDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validation of skill definition / promptTemplate.
 *
 * <p>The skill body must follow a fixed heading structure: {@code # <skill name>} (exactly one H1) +
 * {@code ## stepN <step name>} (at least one) + {@code ### stepN.M <sub-step name>} (nested under
 * the nearest preceding {@code ## stepN} with the same number), with no level jumps. The UI form
 * (service) and the agent skill tool share this validation — both go through it, and a single
 * implementation prevents the rules from drifting.
 *
 * <p>{@link #validate(String)} returns {@code null} when valid, otherwise the "violation list + the
 * standard template".
 */
public final class SkillTemplateValidator {

    /** Canonical template: kept in sync with SKILL_TEMPLATE in the frontend {@code agent-sphere-ui/src/pages/capabilities/skill/index.tsx}. */
    public static final String SKILL_TEMPLATE = """
            # <Skill name>

            ## Overview
            <When to use / input and output / success criteria>

            ## step0 <Preparation>
            ### step0.1 <Sub-step>
            ### step0.2 <Sub-step>

            ## step1 <Main step>
            ### step1.1 <Sub-step>
            ### step1.2 <Sub-step>

            ## step2 <Main step>

            ## Wrap-up checklist
            <Closing checks that must pass before completion; output format / acceptance criteria>""";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MARKDOWN_FENCE = "```json";

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern STEP_H2 = Pattern.compile("^step\\s*\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern STEP_H3 = Pattern.compile("^step\\s*\\d+\\.\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern STEP_H3_PARENT = Pattern.compile("^(step\\s*\\d+)\\.", Pattern.CASE_INSENSITIVE);

    private SkillTemplateValidator() {
    }

    /**
     * Validates the definition: JSON shape (parameters + promptTemplate) → promptTemplate heading
     * structure.
     *
     * @return {@code null} when valid; otherwise error text that can be returned to the user as-is
     *         (violations plus the template)
     */
    public static String validate(String definition) {
        if (definition == null || definition.isBlank()) {
            return null; // an empty definition keeps the existing pass-through semantics (constrained separately by each entry point)
        }
        SkillDefinition def;
        try {
            def = SkillDefinitionParser.parse(definition);
        } catch (InvalidSubRunDefinition e) {
            return "Invalid definition: " + e.getMessage();
        }
        if (def == null) {
            return null;
        }
        return validateStructure(def.promptTemplate());
    }

    /** Structural validation; returns null when compliant. */
    static String validateStructure(String promptTemplate) {
        if (promptTemplate == null || promptTemplate.isBlank()) {
            return structureError(List.of("promptTemplate is blank"));
        }
        List<Heading> headings = parseHeadings(promptTemplate);
        List<String> problems = new ArrayList<>();

        long h1 = headings.stream().filter(h -> h.level() == 1).count();
        if (h1 != 1) {
            problems.add("Exactly one H1 title is required (# skill name); found " + h1);
        }
        if (headings.isEmpty() || headings.get(0).level() != 1) {
            problems.add("The first heading must be H1 (# skill name)");
        }
        boolean hasStep = headings.stream()
                .anyMatch(h -> h.level() == 2 && STEP_H2.matcher(h.text()).find());
        if (!hasStep) {
            problems.add("At least one step heading is required (## stepN step name)");
        }

        int prevLevel = 0;
        String currentStep = null; // normalized prefix of the nearest ## stepN above (null for a non-step H2)
        for (Heading h : headings) {
            int lvl = h.level();
            String text = h.text();
            if (prevLevel != 0 && lvl > prevLevel + 1) {
                problems.add("L" + h.line() + " heading level jump: H" + prevLevel + " -> H" + lvl + " (\"" + text + "\")");
            }
            if (lvl == 2) {
                Matcher m = STEP_H2.matcher(text);
                currentStep = m.find() ? normStepId(m.group()) : null;
            } else if (lvl == 3) {
                Matcher m3 = STEP_H3.matcher(text);
                if (!m3.find()) {
                    problems.add("L" + h.line() + " sub-step must use `### stepN.M sub-step name` (current: \"" + text + "\")");
                } else {
                    Matcher mp = STEP_H3_PARENT.matcher(text);
                    String parent = mp.find() ? normStepId(mp.group(1)) : null;
                    if (currentStep == null || !currentStep.equals(parent)) {
                        problems.add("L" + h.line() + " sub-step \"" + text + "\" is not under the same numbered step (current parent: "
                                + (currentStep == null ? "none; write ## stepN first" : "## " + currentStep) + ")");
                    }
                }
            }
            prevLevel = lvl;
        }

        return problems.isEmpty() ? null : structureError(problems);
    }

    private static String structureError(List<String> problems) {
        StringBuilder sb = new StringBuilder("The definition does not conform to the skill structure rules:");
        for (String p : problems) {
            sb.append("\n- ").append(p);
        }
        sb.append("\nRewrite promptTemplate using the following template:\n\n").append(SKILL_TEMPLATE);
        return sb.toString();
    }

    private static String normStepId(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static List<Heading> parseHeadings(String markdown) {
        List<Heading> out = new ArrayList<>();
        String[] lines = markdown.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = HEADING.matcher(lines[i]);
            if (m.matches()) {
                out.add(new Heading(i + 1, m.group(1).length(), m.group(2).trim()));
            }
        }
        return out;
    }

    private record Heading(int line, int level, String text) {
    }
}
