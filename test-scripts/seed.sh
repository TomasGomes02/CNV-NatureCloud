#!/bin/bash

# Port 8080 based on your LoadBalancer configuration
LB_URL="http://localhost:8080"

echo "========================================================="
echo "Starting HIGH-DENSITY KNN Grid Generator (439 Points)..."
echo "Target: $LB_URL"
echo "========================================================="
echo "Waiting 5s for the cluster to stabilize..."
sleep 5

batch_count=0
MAX_CONCURRENT=4 # Safe for a modern laptop. Bump to 6 or 8 if you have an 8-core CPU!

# ==========================================
# 1. FRACTALS
# ==========================================
echo ""
echo "[1/3] Sweeping Fractals Space (175 points)..."

for w in 800 1500 2500 3000 4000 6000 7500; do
    for h in 600 1200 2400 4000 4500 6000; do
        for i in 100 500 2500 25000 50000 1000000; do

            echo " -> Fractal: ${w}x${h}, iters=${i}"
            curl -s "$LB_URL/fractals?w=${w}&h=${h}&iterations=${i}" > /dev/null &

            ((batch_count++))
            if (( batch_count % MAX_CONCURRENT == 0 )); then wait; fi
            sleep 1
        done
    done
done
wait # Ensure all fractals finish before moving to the next batch


# ==========================================
# 2. GRAY-SCOTT
# ==========================================
echo ""
echo "[2/3] Sweeping Gray-Scott Space (120 points)..."

for s in 128 256 512; do
    for i in 100 300 700 800 1500; do
        for ext in true false; do
            for mode in center ring stripe; do

                echo " -> GrayScott: size=${s}, iters=${i}, ext=${ext}, mode=${mode}"
                curl -s "$LB_URL/grayscott?size=${s}&maxIterations=${i}&f=0.030&k=0.062&stopOnExtinction=${ext}&seedMode=${mode}" > /dev/null &

                ((batch_count++))
                if (( batch_count % MAX_CONCURRENT == 0 )); then wait; fi
                sleep 1
            done
        done
    done
done
wait


# ==========================================
# 3. DNA
# ==========================================
echo ""
echo "[3/3] Sweeping Dense DNA Space (144 points)..."

# Base String (~438 characters)
DNA_BASE="ATGGTGCATCTGACTCCTGAGGAGAAGTCTGCCGTTACTGCCCTGTGGGGCAAGGTGAACGTGGATGAAGTTGGTGGTGAGGCCCTGGGCAGGCTGCTGGTGGTCTACCCTTGGACCCAGAGGTTCTTTGAGTCCTTTGGGGATCTGTCCACTCCTGATGCTGTTATGGGCAACCCTAAGGTGAAGGCTCATGGCAAGAAAGTGCTCGGTGCCTTTAGTGATGGCCTGGCTCACCTGGACAACCTCAAGGGCACCTTTGCCACACTGAGTGAGCTGCACTGTGACAAGCTGCACGTGGATCCTGAGAACTTCAGGCTCCTGGGCAACGTGCTGGTCTGTGTGCTGGCCCATCACTTTGGCAAAGAATTCACCCCACCAGTGCAGGCTGCCTATCAGAAAGTGGTGGCTGGTGTGGCTAATGCCCTGGCCCACAAGTATCACTAA"

# Build a massive 26,000+ character string in memory
GIANT_SEQ=""
for b in {1..60}; do
    GIANT_SEQ="${GIANT_SEQ}${DNA_BASE}"
done

for len1 in 400 2000 5000 8000 10000 15000 25000; do
    for len2 in 400 2000 5000 8000 10000 15000 25000; do
        for min in 1 50 150 250; do

            # Bash substring extraction: ${string:position:length}
            seq1="human_HBB:${GIANT_SEQ:0:$len1}"
            seq2="chimpanzee_HBB:${GIANT_SEQ:0:$len2}"

            echo " -> DNA: seq1Len=${len1}, seq2Len=${len2}, minLength=${min}"

            curl -s "$LB_URL/dna" -G \
                --data-urlencode "seq1=$seq1" \
                --data-urlencode "seq2=$seq2" \
                --data-urlencode "minLength=${min}" \
                --data-urlencode "stopOnFirst=false" > /dev/null &

            ((batch_count++))
            if (( batch_count % MAX_CONCURRENT == 0 )); then wait; fi
            sleep 1
        done
    done
done
wait

echo "---------------------------------------------------------"
echo "Exhaustive Grid Search Complete!"
echo "---------------------------------------------------------"