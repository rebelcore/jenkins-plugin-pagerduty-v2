package io.jenkins.plugins.pagerdutyv2;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@WithJenkins
public class PagerDutyV2GlobalConfigurationTest {

    @Test
    void serviceChoicesParsingTrimsAndDedupes(JenkinsRule j) {
        PagerDutyV2GlobalConfiguration cfg = PagerDutyV2GlobalConfiguration.get();
        cfg.setServiceChoicesRaw(" svc-a , svc-b\nsvc-b\nsvc-c\n  \nsvc-a");

        List<String> parsed = cfg.getServiceChoices();
        assertEquals(List.of("svc-a", "svc-b", "svc-c"), parsed);
    }
}
