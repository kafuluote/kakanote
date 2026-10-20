package update

import (
	"errors"
	"fmt"
	"strconv"
	"strings"
)

var ErrInvalidVersion = errors.New("version must contain three non-negative integers, for example 1.2.0")

// Version 使用本项目的版本规则：第二段为大版本，第三段为小版本。
// 不使用 SemVer 中第一段 major、第二段 minor 的命名。
type Version struct {
	Generation uint64
	Major      uint64
	Minor      uint64
}

func ParseVersion(value string) (Version, error) {
	parts := strings.Split(value, ".")
	if len(parts) != 3 {
		return Version{}, ErrInvalidVersion
	}
	var numbers [3]uint64
	for i, part := range parts {
		if part == "" || (len(part) > 1 && part[0] == '0') {
			return Version{}, ErrInvalidVersion
		}
		for _, c := range part {
			if c < '0' || c > '9' {
				return Version{}, ErrInvalidVersion
			}
		}
		n, err := strconv.ParseUint(part, 10, 64)
		if err != nil {
			return Version{}, ErrInvalidVersion
		}
		numbers[i] = n
	}
	return Version{numbers[0], numbers[1], numbers[2]}, nil
}

func (v Version) String() string {
	return fmt.Sprintf("%d.%d.%d", v.Generation, v.Major, v.Minor)
}

func (v Version) Compare(other Version) int {
	left := [3]uint64{v.Generation, v.Major, v.Minor}
	right := [3]uint64{other.Generation, other.Major, other.Minor}
	for i := range left {
		if left[i] > right[i] {
			return 1
		}
		if left[i] < right[i] {
			return -1
		}
	}
	return 0
}

type Policy struct {
	ForceMajor bool `json:"forceMajor"`
	ForceMinor bool `json:"forceMinor"`
}

type Decision struct {
	UpdateAvailable bool   `json:"updateAvailable"`
	ForceUpdate     bool   `json:"forceUpdate"`
	UpdateType      string `json:"updateType"`
}

func Decide(current, latest Version, policy Policy) Decision {
	if latest.Compare(current) <= 0 {
		// 相同或更高版本绝不提示更新、强制更新或降级。
		return Decision{UpdateType: "none"}
	}
	if latest.Generation != current.Generation || latest.Major != current.Major {
		return Decision{true, policy.ForceMajor, "major"}
	}
	return Decision{true, policy.ForceMinor, "minor"}
}
