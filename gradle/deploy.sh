set -eu
session=$1 window=$2 folder=$3 artifact=$4 prefix=$5 repo_url=$6 branch=$7 repo=$8 artifact_prefix=$9 target="$session:$window.0"
if [ ! -d "$repo/.git" ]; then
  mkdir -p "$(dirname "$repo")"
  git clone "$repo_url" "$repo" || { echo "Failed to clone $repo_url into $repo" >&2; exit 1; }
fi
tmux has-session -t "$session" 2>/dev/null ||
  tmux new-session -d -s "$session" -n "$window" -c "$folder"
tmux list-windows -t "$session" -F '#{window_name}' | grep -Fxq "$window" ||
  tmux new-window -d -t "$session" -n "$window" -c "$folder"
tmux send-keys -t "$target" Escape C-u C-c
pane_pid=$(tmux display-message -p -t "$target" '#{pane_pid}')
attempt=0
while foreground_group=$(ps -o tpgid= -p "$pane_pid");
  ps -e -o pgid=,comm= | awk -v pgid="$foreground_group" '$1 == pgid && $2 !~ /^(bash|zsh|fish|sh)$/ { running=1 } END { exit !running }'; do
  attempt=$((attempt + 1))
  [ "$attempt" -lt 3000 ] || { echo "Server in tmux target $target did not stop within 5 minutes" >&2; exit 1; }
  sleep .1
done
# Remove stale Voxy artifacts before installing the new build.
find "$folder/mods" -maxdepth 1 -type f -iname "*$artifact_prefix*.jar" -delete
mv "$folder/.$prefix-deploy.jar" "$folder/mods/$artifact"
tmux send-keys -t "$target" C-u
tmux send-keys -t "$target" -l "cd $folder && ./run.sh"
tmux send-keys -t "$target" Enter
git -C "$repo" fetch origin
git -C "$repo" switch "$branch"
git -C "$repo" pull --ff-only origin "$branch"
