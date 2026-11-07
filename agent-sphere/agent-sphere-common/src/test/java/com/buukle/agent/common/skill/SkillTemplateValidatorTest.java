package com.buukle.agent.common.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rule guardrails for skill structure validation (shared by the UI form and the agent tool). */
class SkillTemplateValidatorTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String OK = "# Skill\n\n## step1 Step\n### step1.1 Sub-step\nBody";

    private static String def(String promptTemplate) {
        ObjectNode root = M.createObjectNode();
        root.put("version", 1);
        root.putObject("parameters").put("type", "object").putObject("properties");
        root.put("promptTemplate", promptTemplate);
        return root.toString();
    }

    @Test
    void conformant_passes() {
        assertNull(SkillTemplateValidator.validate(def(OK)));
    }

    @Test
    void blankOrNull_passes() {
        assertNull(SkillTemplateValidator.validate(""));
        assertNull(SkillTemplateValidator.validate(null));
    }

    @Test
    void legacyPromptWithoutStructure_isAlsoRejected() {
        // strict validation: a legacy {"prompt":...} without heading structure → also rejected
        String err = SkillTemplateValidator.validate("{\"prompt\":\"do it\"}");
        assertNotNull(err);
        assertTrue(err.contains("does not conform"), err);
    }

    @Test
    void missingH1_rejectedWithTemplate() {
        String err = SkillTemplateValidator.validate(def("## step1 Step\nBody"));
        assertNotNull(err);
        assertTrue(err.contains("H1"), err);
        assertTrue(err.contains("# <Skill name>"), "should include the standard template: " + err);
    }

    @Test
    void missingStepHeading_rejected() {
        String err = SkillTemplateValidator.validate(def("# Skill\n\n## Overview\nBody"));
        assertNotNull(err);
        assertTrue(err.contains("step heading"), err);
    }

    @Test
    void substepUnderWrongStep_rejected() {
        String err = SkillTemplateValidator.validate(def("# Skill\n\n## step1 A\n\n## step2 B\n### step1.1 X\n"));
        assertNotNull(err);
        assertTrue(err.contains("step1.1"), err);
    }

    @Test
    void levelJump_rejected() {
        String err = SkillTemplateValidator.validate(def("# Skill\n\n### step1.1 Deep jump\n\n## step1 A\n"));
        assertNotNull(err);
        assertTrue(err.contains("heading level jump"), err);
    }

    @Test
    void nonStepH3_rejected() {
        String err = SkillTemplateValidator.validate(def("# Skill\n\n## Overview\n### Background\n\n## step1 A\n"));
        assertNotNull(err);
    }

    @Test
    void malformedJson_rejected() {
        assertTrue(SkillTemplateValidator.validate("not-json").contains("Invalid definition"));
    }
}
