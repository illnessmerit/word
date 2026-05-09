{
  pkgs,
  ...
}:

{
  # https://devenv.sh/basics/
  env.GREET = "devenv";

  # https://devenv.sh/packages/
  packages = [
    pkgs.git
    pkgs.gitleaks
    pkgs.nil
    pkgs.pre-commit
    pkgs.python313Packages.pre-commit-hooks
    pkgs.rubyPackages.solargraph
  ];

  # https://devenv.sh/languages/
  # languages.rust.enable = true;
  languages.clojure.enable = true;

  # https://devenv.sh/processes/
  # processes.dev.exec = "${lib.getExe pkgs.watchexec} -n -- ls -la";

  # https://devenv.sh/services/
  # services.postgres.enable = true;

  # https://devenv.sh/scripts/
  scripts.hello.exec = ''
    echo hello from $GREET
  '';
  scripts.release.exec = "shadow-cljs release main";
  scripts.run.exec = ''
    nvim +"lua vim.fn.Style(1)"
  '';

  # https://devenv.sh/basics/
  enterShell = ''
    hello         # Run scripts directly
    git --version # Use packages
    brew bundle
    npm i
    export PATH="$DEVENV_ROOT/node_modules/.bin:$PATH"
    export NVIM_NODE_LOG_FILE="$DEVENV_ROOT/node.log"
    sed "s|{{dir}}|$DEVENV_ROOT|g" template.lua > "$HOME"/.config/nvim/lua/plugins/word.lua
  '';

  # https://devenv.sh/tasks/
  # tasks = {
  #   "myproj:setup".exec = "mytool build";
  #   "devenv:enterShell".after = [ "myproj:setup" ];
  # };

  # https://devenv.sh/tests/
  enterTest = ''
    echo "Running tests"
    git --version | grep --color=auto "${pkgs.git.version}"
  '';

  # https://devenv.sh/git-hooks/
  # git-hooks.hooks.shellcheck.enable = true;
  git-hooks.hooks = {
    cljfmt.enable = true;
    gitleaks = {
      enable = true;
      # https://github.com/gitleaks/gitleaks/blob/8863af47d64c3681422523e36837957c74d4af4b/.pre-commit-hooks.yaml#L4
      # Direct execution of gitleaks here results in '[git] fatal: cannot change to 'devenv.nix': Not a directory'.
      entry = "bash -c 'exec gitleaks git --redact --staged --verbose'";
    };
    lua-ls.enable = true;
    # https://github.com/NixOS/nixfmt/blob/f723c1c1aaa91908d2fa66f0432fd2c5db9c21a1/README.md?plain=1#L169
    nixfmt.enable = true;
    prettier.enable = true;
    stylua.enable = true;
    trailing-whitespace = {
      enable = true;
      # https://github.com/pre-commit/pre-commit-hooks/blob/5c514f85cc9be49324a6e3664e891ac2fc8a8609/.pre-commit-hooks.yaml#L205-L212
      entry = "trailing-whitespace-fixer";
      types = [ "text" ];
    };
  };

  # See full reference at https://devenv.sh/reference/options/
}
