# Sourced by the probe scripts. level_type prints the level-type for the server in the current directory:
# $LEVEL_TYPE when set, else RWG2_CONTINENTS when RWG2 is installed (pack 2.9.0 defaults to it), else rwg.
level_type() {
  if [ -n "${LEVEL_TYPE:-}" ]; then
    echo "$LEVEL_TYPE"
  elif ls mods/rwg2-*.jar >/dev/null 2>&1; then
    echo RWG2_CONTINENTS
  else
    echo rwg
  fi
}
