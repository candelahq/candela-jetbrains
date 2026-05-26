{
  description = "Candela JetBrains — Kotlin plugin development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      supportedSystems = [ "x86_64-linux" "aarch64-darwin" "x86_64-darwin" "aarch64-linux" ];
      forEachSupportedSystem = f: nixpkgs.lib.genAttrs supportedSystems (system: f {
        pkgs = import nixpkgs { inherit system; };
        inherit system;
      });
    in {
      devShells = forEachSupportedSystem ({ pkgs, ... }: {
        default = pkgs.mkShell {
          buildInputs = with pkgs; [
            # JDK 21 (required by IntelliJ Platform Plugin 2.x)
            temurin-bin-21

            # Build tools
            gradle
            kotlin

            # Dev tools
            git
            lefthook
          ];

          shellHook = ''
            export JAVA_HOME="${pkgs.temurin-bin-21}"
            echo ""
            echo "🕯️  Candela JetBrains dev shell"
            echo "   Java    : $(java -version 2>&1 | head -1)"
            echo "   Gradle  : $(gradle --version 2>/dev/null | grep '^Gradle' || echo 'available')"
            echo "   Kotlin  : $(kotlin -version 2>&1 || echo 'available')"
            echo ""
          '';
        };
      });
    };
}
