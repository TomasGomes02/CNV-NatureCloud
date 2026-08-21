#!/bin/bash

# Change this to your current Load Balancer IP/DNS!
TARGET_HOST="http://ec2-13-40-104-195.eu-west-2.compute.amazonaws.com"
TIMEOUT=120 # 2 minutes max per request

echo "======================================================"
echo "    NATURE@CLOUD - 4-TIER WAVE STRESS TEST            "
echo "======================================================"
echo "Target: $TARGET_HOST"
echo "Format: 6 Concurrent Requests per Wave (2 Frac, 2 GS, 2 DNA)"
echo "Waiting 5 seconds to begin..."
sleep 5

# Helper function to read FASTA files securely
get_fasta() {
    if [ -f "$1" ]; then
        cat "$1"
    else
        echo "ATGC" # Fallback so the script doesn't crash if the file is missing
    fi
}

# Pre-load the DNA content
DNA_S1=$(get_fasta "sars-10k.fasta")
DNA_S2=$(get_fasta "human-mc-10k.fasta")
DNA_XL1=$(get_fasta "genome-escherichia-coli-25k.fasta")
DNA_XL2=$(get_fasta "salmonella-enterica-25k.fasta")

# ==========================================
# WAVE 1: SMALL (S) - The Warmup
# ==========================================
echo -e "\n[WAVE 1] Launching 'S' (Small) Batch..."

curl -s --max-time $TIMEOUT "${TARGET_HOST}/fractals?w=4000&h=2000&iterations=1000" > /dev/null &
curl -s --max-time $TIMEOUT "${TARGET_HOST}/fractals?w=4000&h=2000&iterations=1000" > /dev/null &

curl -s --max-time $TIMEOUT "${TARGET_HOST}/grayscott?size=1024&maxIterations=10000&f=0.230&k=0.062&stopOnExtinction=true&seedMode=ring" > /dev/null &
curl -s --max-time $TIMEOUT "${TARGET_HOST}/grayscott?size=1024&maxIterations=10000&f=0.230&k=0.062&stopOnExtinction=true&seedMode=ring" > /dev/null &

curl -s --max-time $TIMEOUT -G "${TARGET_HOST}/dna?minLength=200&stopOnFirst=False" --data-urlencode "seq1=sars-10k:$DNA_S1" --data-urlencode "seq2=human-mc-10k:$DNA_S2" > /dev/null &
curl -s --max-time $TIMEOUT -G "${TARGET_HOST}/dna?minLength=200&stopOnFirst=False" --data-urlencode "seq1=sars-10k:$DNA_S1" --data-urlencode "seq2=human-mc-10k:$DNA_S2" > /dev/null &

wait
echo "Wave 1 Complete. Waiting 10s for AutoScaler evaluation..."
sleep 10


# ==========================================
# WAVE 2: MEDIUM (M) - The Baseline
# ==========================================
echo -e "\n[WAVE 2] Launching 'M' (Medium) Batch..."

curl -s --max-time $TIMEOUT "${TARGET_HOST}/fractals?w=5000&h=3000&iterations=25000" > /dev/null &
curl -s --max-time $TIMEOUT "${TARGET_HOST}/fractals?w=5000&h=3000&iterations=25000" > /dev/null &

curl -s --max-time $TIMEOUT "${TARGET_HOST}/grayscott?size=256&maxIterations=5000&f=0.030&k=0.062&stopOnExtinction=false&seedMode=center" > /dev/null &
curl -s --max-time $TIMEOUT "${TARGET_HOST}/grayscott?size=256&maxIterations=5000&f=0.030&k=0.062&stopOnExtinction=false&seedMode=center" > /dev/null &

curl -s --max-time $TIMEOUT -G "${TARGET_HOST}/dna?minLength=150&stopOnFirst=False" --data-urlencode "seq1=sars-10k:$DNA_S1" --data-urlencode "seq2=salmonella-enterica-25k:$DNA_XL2" > /dev/null &
curl -s --max-time $TIMEOUT -G "${TARGET_HOST}/dna?minLength=150&stopOnFirst=False" --data-urlencode "seq1=sars-10k:$DNA_S1" --data-urlencode "seq2=salmonella-enterica-25k:$DNA_XL2" > /dev/null &

wait
echo "Wave 2 Complete. Waiting 10s for AutoScaler evaluation..."
sleep 10


# ==========================================
# WAVE 3: LARGE (L) - The Scale-Up Trigger (Loops 3x)
# ==========================================
for step in {0..2}; do
    echo -e "\n[WAVE 3] Launching 'L' (Large) Batch (Loop $((step+1))/3)..."
    
    # Increase iteration/length by 1 per loop to bypass cache!
    F_ITER=$((150000 + step))
    G_ITER=$((5000 + step))
    D_MIN=$((100 + step))

    curl -s --max-time $TIMEOUT "${TARGET_HOST}/fractals?w=6000&h=4500&iterations=${F_ITER}" > /dev/null &
    curl -s --max-time $TIMEOUT "${TARGET_HOST}/fractals?w=6000&h=4500&iterations=${F_ITER}" > /dev/null &

    curl -s --max-time $TIMEOUT "${TARGET_HOST}/grayscott?size=512&maxIterations=${G_ITER}&f=0.030&k=0.062&stopOnExtinction=false&seedMode=stripe" > /dev/null &
    curl -s --max-time $TIMEOUT "${TARGET_HOST}/grayscott?size=512&maxIterations=${G_ITER}&f=0.030&k=0.062&stopOnExtinction=false&seedMode=stripe" > /dev/null &

    curl -s --max-time $TIMEOUT -G "${TARGET_HOST}/dna?minLength=${D_MIN}&stopOnFirst=False" --data-urlencode "seq1=escherichia-coli-25k:$DNA_XL1" --data-urlencode "seq2=human-mc-10k:$DNA_S2" > /dev/null &
    curl -s --max-time $TIMEOUT -G "${TARGET_HOST}/dna?minLength=${D_MIN}&stopOnFirst=False" --data-urlencode "seq1=escherichia-coli-25k:$DNA_XL1" --data-urlencode "seq2=human-mc-10k:$DNA_S2" > /dev/null &

    wait
    sleep 2 # Brief pause between sub-loops
done

echo "Wave 3 Complete. Waiting 10s..."
sleep 10


# ==========================================
# WAVE 4: EXTRA LARGE (XL) - The Lambda Trigger (Loops 6x)
# ==========================================
for step in {0..3}; do
    echo -e "\n[WAVE 4] Launching 'XL' (Extra Large) Batch (Loop $((step+1))/4)..."
    
    # Increase iteration/length by 1 per loop to bypass cache!
    F_ITER=$((1000000 + step))
    G_ITER=$((1500 + step))
    D_MIN=$((250 + step))

    curl -s --max-time $TIMEOUT "${TARGET_HOST}/fractals?w=7500&h=6000&iterations=${F_ITER}" > /dev/null &
    curl -s --max-time $TIMEOUT "${TARGET_HOST}/fractals?w=7500&h=6000&iterations=${F_ITER}" > /dev/null &

    curl -s --max-time $TIMEOUT "${TARGET_HOST}/grayscott?size=512&maxIterations=${G_ITER}&f=0.030&k=0.062&stopOnExtinction=false&seedMode=ring" > /dev/null &
    curl -s --max-time $TIMEOUT "${TARGET_HOST}/grayscott?size=512&maxIterations=${G_ITER}&f=0.030&k=0.062&stopOnExtinction=false&seedMode=ring" > /dev/null &

    curl -s --max-time $TIMEOUT -G "${TARGET_HOST}/dna?minLength=${D_MIN}&stopOnFirst=False" --data-urlencode "seq1=escherichia-coli-25k:$DNA_XL1" --data-urlencode "seq2=salmonella-enterica-25k:$DNA_XL2" > /dev/null &
    curl -s --max-time $TIMEOUT -G "${TARGET_HOST}/dna?minLength=${D_MIN}&stopOnFirst=False" --data-urlencode "seq1=escherichia-coli-25k:$DNA_XL1" --data-urlencode "seq2=salmonella-enterica-25k:$DNA_XL2" > /dev/null &

    wait
    sleep 2 # Brief pause between sub-loops
done

echo "Wave 4 Complete. Waiting 10s..."
sleep 10


# ==========================================
# WAVE 5: NEW BATCH MIX - The Final Burn (Loops 4x)
# ==========================================
for step in {0..3}; do
    echo -e "\n[WAVE 5] Launching Custom Mix Batch (Loop $((step+1))/4)..."
    
    # Increase iteration/length by 1 per loop to bypass cache!
    FA_ITER=$((101101 + step))
    FB_ITER=$((1000001 + step))
    G_ITER=$((1499 + step))
    DA_MIN=$((25 + step))
    DB_MIN=$((2 + step))

    # Fractals (Requests A and B)
    curl -s --max-time $TIMEOUT "${TARGET_HOST}/fractals?w=6100&h=6100&iterations=${FA_ITER}" > /dev/null &
    curl -s --max-time $TIMEOUT "${TARGET_HOST}/fractals?w=7499&h=5999&iterations=${FB_ITER}" > /dev/null &

    # Gray-Scott (Requests A and B are identical in the prompt, so running 2 of them)
    curl -s --max-time $TIMEOUT "${TARGET_HOST}/grayscott?size=512&maxIterations=${G_ITER}&f=0.031&k=0.063&stopOnExtinction=true&seedMode=center" > /dev/null &
    curl -s --max-time $TIMEOUT "${TARGET_HOST}/grayscott?size=512&maxIterations=${G_ITER}&f=0.031&k=0.063&stopOnExtinction=true&seedMode=center" > /dev/null &

    # DNA (Requests A and B)
    curl -s --max-time $TIMEOUT -G "${TARGET_HOST}/dna?minLength=${DA_MIN}&stopOnFirst=False" --data-urlencode "seq1=escherichia-coli-25k:$DNA_XL1" --data-urlencode "seq2=salmonella-enterica-25k:$DNA_XL2" > /dev/null &
    curl -s --max-time $TIMEOUT -G "${TARGET_HOST}/dna?minLength=${DB_MIN}&stopOnFirst=True" --data-urlencode "seq1=sars-10k:$DNA_S1" --data-urlencode "seq2=human-mc-10k:$DNA_S2" > /dev/null &

    wait
    sleep 2 # Brief pause between sub-loops
done


echo -e "\n======================================================"
echo "TEST COMPLETE!"
echo "Check your Load Balancer SSH console to see the scaling events!"
echo "======================================================"