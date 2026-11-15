# 1. Sube el workflow a GitHub
git add .github/workflows/release.yml
git commit -m "Agrega workflow de release para compilar en la nube"
git push origin main

# 2. Lanza el workflow (compila en la nube, no en tu PC)
gh workflow run release.yml --repo angelonmx/reloquenceTTS -f tag=v1.1.2
gh run watch --repo angelonmx/reloquenceTTS