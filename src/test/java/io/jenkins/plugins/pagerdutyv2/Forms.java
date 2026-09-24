// Copyright 2010 Rebel Media
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.jenkins.plugins.pagerdutyv2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.util.List;

/** Reading dropdowns and field checks the way a configuration page shows them. */
final class Forms {

    private Forms() {}

    /** The values of a dropdown's options, in the order the page lists them. */
    static List<String> values(ListBoxModel m) {
        return m.stream().map(o -> o.value).toList();
    }

    /** The value of the option the page shows as selected, or null. */
    static String selected(ListBoxModel m) {
        return m.stream().filter(o -> o.selected).map(o -> o.value).findFirst().orElse(null);
    }

    static void assertOk(FormValidation v) {
        assertEquals(FormValidation.Kind.OK, v.kind, v.getMessage());
    }

    static void assertError(String message, FormValidation v) {
        assertEquals(FormValidation.Kind.ERROR, v.kind);
        assertEquals(message, v.getMessage());
    }
}
