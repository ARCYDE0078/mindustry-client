#!/usr/bin/env bash
# Тегает и пушит новый релиз кастомного клиента. Схема версий: custom-b<YYYY.MM.DD>,
# при повторном релизе в тот же день добавляется суффикс .2, .3 итд, чтобы теги не совпадали.
# Тег ловит .github/workflows/release-custom.yml (триггер custom-b*), который сам соберёт
# джарник и опубликует GitHub Release - руками собирать и заливать jar не нужно.
set -euo pipefail

REMOTE="${1:-promiha}"

git fetch --tags "$REMOTE" --quiet

today=$(date +%Y.%m.%d)
base="custom-b$today"
tag="$base"
n=2
while git ls-remote --tags "$REMOTE" "refs/tags/$tag" | grep -q "$tag"; do
    tag="$base.$n"
    n=$((n + 1))
done

echo "Tagging HEAD as $tag and pushing to $REMOTE"
git tag "$tag"
git push "$REMOTE" "$tag"
echo "Done: $tag"
