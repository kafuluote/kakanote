package update

import (
	"errors"
	"testing"
)

func TestUpdateDecisions(t *testing.T) {
	policy := Policy{ForceMajor: true}
	tests := []struct {
		name, current, latest string
		want                  Decision
	}{
		{"minor optional", "1.2.0", "1.2.1", Decision{true, false, "minor"}},
		{"major forced", "1.2.99", "1.3.0", Decision{true, true, "major"}},
		{"skipped major", "1.1.999", "1.3.4", Decision{true, true, "major"}},
		{"generation forced", "1.99.999", "2.0.0", Decision{true, true, "major"}},
		{"equal", "1.2.0", "1.2.0", Decision{UpdateType: "none"}},
		{"newer minor", "1.2.10", "1.2.9", Decision{UpdateType: "none"}},
		{"newer major", "1.3.0", "1.2.999", Decision{UpdateType: "none"}},
		{"newer generation", "2.0.0", "1.99.999", Decision{UpdateType: "none"}},
		{"numeric minor", "1.2.9", "1.2.10", Decision{true, false, "minor"}},
		{"numeric major", "1.9.99", "1.10.0", Decision{true, true, "major"}},
		{"zero", "0.0.0", "0.0.1", Decision{true, false, "minor"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			current, err := ParseVersion(tt.current)
			if err != nil {
				t.Fatal(err)
			}
			latest, err := ParseVersion(tt.latest)
			if err != nil {
				t.Fatal(err)
			}
			if got := Decide(current, latest, policy); got != tt.want {
				t.Fatalf("%s -> %s: got %+v, want %+v", tt.current, tt.latest, got, tt.want)
			}
		})
	}
}

func TestPolicyOverrides(t *testing.T) {
	current := Version{1, 2, 0}
	if got := Decide(current, Version{1, 3, 0}, Policy{}); got.ForceUpdate {
		t.Fatal("forceMajor=false must allow optional major updates")
	}
	if got := Decide(current, Version{1, 2, 1}, Policy{ForceMinor: true}); !got.ForceUpdate {
		t.Fatal("forceMinor=true must force minor updates")
	}
	if got := Decide(current, current, Policy{true, true}); got.ForceUpdate || got.UpdateAvailable {
		t.Fatal("policy must never force an equal version")
	}
}

func TestParseVersionRejectsInvalidInput(t *testing.T) {
	for _, input := range []string{
		"", "1", "1.2", "1.2.3.4", "1..0", "-1.2.0", "1.-2.0",
		"1.2.-1", "+1.2.0", "1.2.a", "v1.2.0", "1.2.0-DEBUG",
		" 1.2.0", "1.2.0 ", "01.2.0", "1.02.0", "1.2.00",
		"1.2.18446744073709551616",
	} {
		t.Run(input, func(t *testing.T) {
			if _, err := ParseVersion(input); !errors.Is(err, ErrInvalidVersion) {
				t.Fatalf("expected invalid version for %q, got %v", input, err)
			}
		})
	}
}
