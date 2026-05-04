import argparse
import csv
from collections import namedtuple
from pathlib import Path

import numpy as np
from scipy.optimize import linear_sum_assignment
from skimage import io
from skimage.segmentation import relabel_sequential


MATCHING_CRITERIA = {}


def _raise(err):
    raise err


def parse_args():
    parser = argparse.ArgumentParser(
        description="Compute validation metrics from Cellpose predictions using raw images, ground-truth masks, and prediction masks."
    )
    parser.add_argument(
        "dir",
        nargs=1,
        help="directory containing raw images, ground-truth masks, and Cellpose prediction masks",
    )
    parser.add_argument(
        "model",
        nargs=1,
        help="model name recorded in the output report",
    )
    parser.add_argument(
        "out_dir",
        nargs="?",
        default=None,
        help="optional output directory for deterministic validation_results.csv",
    )
    return parser.parse_args()


def label_are_sequential(y):
    labels = np.unique(y)
    return (set(labels) - {0}) == set(range(1, 1 + labels.max()))


def is_array_of_integers(y):
    return isinstance(y, np.ndarray) and np.issubdtype(y.dtype, np.integer)


def _check_label_array(y, name=None, check_sequential=False):
    err = ValueError(
        "{label} must be an array of {integers}.".format(
            label="labels" if name is None else name,
            integers=("sequential " if check_sequential else "") + "non-negative integers",
        )
    )
    if not is_array_of_integers(y):
        _raise(err)
    if check_sequential:
        if not label_are_sequential(y):
            _raise(err)
    elif y.min() < 0:
        _raise(err)
    return True


def label_overlap(x, y, check=True):
    if check:
        _check_label_array(x, "x", True)
        _check_label_array(y, "y", True)
        x.shape == y.shape or _raise(ValueError("x and y must have the same shape"))
    return _label_overlap(x, y)


def _label_overlap(x, y):
    x = x.ravel()
    y = y.ravel()
    overlap = np.zeros((1 + x.max(), 1 + y.max()), dtype=np.uint)
    for i in range(len(x)):
        overlap[x[i], y[i]] += 1
    return overlap


def intersection_over_union(overlap):
    _check_label_array(overlap, "overlap")
    if np.sum(overlap) == 0:
        return overlap
    n_pixels_pred = np.sum(overlap, axis=0, keepdims=True)
    n_pixels_true = np.sum(overlap, axis=1, keepdims=True)
    return overlap / (n_pixels_pred + n_pixels_true - overlap)


MATCHING_CRITERIA["iou"] = intersection_over_union


def intersection_over_true(overlap):
    _check_label_array(overlap, "overlap")
    if np.sum(overlap) == 0:
        return overlap
    n_pixels_true = np.sum(overlap, axis=1, keepdims=True)
    return overlap / n_pixels_true


MATCHING_CRITERIA["iot"] = intersection_over_true


def intersection_over_pred(overlap):
    _check_label_array(overlap, "overlap")
    if np.sum(overlap) == 0:
        return overlap
    n_pixels_pred = np.sum(overlap, axis=0, keepdims=True)
    return overlap / n_pixels_pred


MATCHING_CRITERIA["iop"] = intersection_over_pred


def precision(tp, fp, fn):
    return tp / (tp + fp) if tp > 0 else 0


def recall(tp, fp, fn):
    return tp / (tp + fn) if tp > 0 else 0


def accuracy(tp, fp, fn):
    return tp / (tp + fp + fn) if tp > 0 else 0


def f1(tp, fp, fn):
    return (2 * tp) / (2 * tp + fp + fn) if tp > 0 else 0


def _safe_divide(x, y):
    return x / y if y > 0 else 0.0


def matching(y_true, y_pred, thresh=0.5, criterion="iou", report_matches=False):
    _check_label_array(y_true, "y_true")
    _check_label_array(y_pred, "y_pred")
    y_true.shape == y_pred.shape or _raise(
        ValueError(
            "y_true ({y_true.shape}) and y_pred ({y_pred.shape}) have different shapes".format(
                y_true=y_true, y_pred=y_pred
            )
        )
    )
    criterion in MATCHING_CRITERIA or _raise(ValueError("Matching criterion '%s' not supported." % criterion))
    if thresh is None:
        thresh = 0
    thresh = float(thresh) if np.isscalar(thresh) else map(float, thresh)

    y_true, _, map_rev_true = relabel_sequential(y_true)
    y_pred, _, map_rev_pred = relabel_sequential(y_pred)

    overlap = label_overlap(y_true, y_pred, check=False)
    scores = MATCHING_CRITERIA[criterion](overlap)
    assert 0 <= np.min(scores) <= np.max(scores) <= 1

    scores = scores[1:, 1:]
    n_true, n_pred = scores.shape
    n_matched = min(n_true, n_pred)

    def _single(thr):
        not_trivial = n_matched > 0 and np.any(scores >= thr)
        if not_trivial:
            costs = -(scores >= thr).astype(float) - scores / (2 * n_matched)
            true_ind, pred_ind = linear_sum_assignment(costs)
            assert n_matched == len(true_ind) == len(pred_ind)
            match_ok = scores[true_ind, pred_ind] >= thr
            tp = np.count_nonzero(match_ok)
        else:
            tp = 0
            true_ind = pred_ind = match_ok = None

        fp = n_pred - tp
        fn = n_true - tp
        sum_matched_score = np.sum(scores[true_ind, pred_ind][match_ok]) if not_trivial else 0.0
        mean_matched_score = _safe_divide(sum_matched_score, tp)
        mean_true_score = _safe_divide(sum_matched_score, n_true)
        panoptic_quality = _safe_divide(sum_matched_score, tp + fp / 2 + fn / 2)

        stats_dict = dict(
            criterion=criterion,
            thresh=thr,
            fp=fp,
            tp=tp,
            fn=fn,
            precision=precision(tp, fp, fn),
            recall=recall(tp, fp, fn),
            accuracy=accuracy(tp, fp, fn),
            f1=f1(tp, fp, fn),
            n_true=n_true,
            n_pred=n_pred,
            mean_true_score=mean_true_score,
            mean_matched_score=mean_matched_score,
            panoptic_quality=panoptic_quality,
        )
        if bool(report_matches):
            if not_trivial:
                stats_dict.update(
                    matched_pairs=tuple(
                        (int(map_rev_true[i]), int(map_rev_pred[j]))
                        for i, j in zip(1 + true_ind, 1 + pred_ind)
                    ),
                    matched_scores=tuple(scores[true_ind, pred_ind]),
                    matched_tps=tuple(map(int, np.flatnonzero(match_ok))),
                )
            else:
                stats_dict.update(matched_pairs=(), matched_scores=(), matched_tps=())
        return namedtuple("Matching", stats_dict.keys())(*stats_dict.values())

    return _single(thresh) if np.isscalar(thresh) else tuple(map(_single, thresh))


def _resolve_output_file(image_folder, model_name, out_dir):
    if out_dir is not None and str(out_dir).strip() != "":
        results_path = Path(out_dir)
        results_path.mkdir(parents=True, exist_ok=True)
        file_path = results_path / "validation_results.csv"
        if file_path.exists():
            raise FileExistsError(f"Refusing to overwrite existing validation results: {file_path}")
        return file_path

    results_path = image_folder / "Validation-Results"
    results_path.mkdir(exist_ok=True)
    return results_path / ("Validation for " + model_name + ".csv")


def _load_validation_triplets(image_folder):
    raw_images = [x for x in Path(image_folder).glob("*.tif") if "masks" not in x.name and "flows" not in x.name]
    if not raw_images:
        raise FileNotFoundError(f"No raw TIFF images were found under: {image_folder}")

    triplets = []
    for raw in raw_images:
        gt_image = raw.parent / f"{raw.stem}_masks{raw.suffix}"
        pred_image = raw.parent / f"{raw.stem}_cp_masks{raw.suffix}"
        if not gt_image.is_file():
            raise FileNotFoundError(f"Missing ground-truth mask file: {gt_image}")
        if not pred_image.is_file():
            raise FileNotFoundError(f"Missing predicted mask file: {pred_image}")
        triplets.append((raw, gt_image, pred_image))
    return triplets


def _binary_mask(image_array):
    return image_array > 0


def compare_labels(model_name, image_folder, out_dir=None):
    image_folder = Path(image_folder)
    triplets = _load_validation_triplets(image_folder)
    file_path = _resolve_output_file(image_folder, model_name, out_dir)

    with open(file_path, "w", newline="", encoding="utf-8") as file:
        writer = csv.writer(file, delimiter=",")
        writer.writerow(
            [
                "model",
                "image",
                "Prediction v. GT Intersection over Union",
                "false positive",
                "true positive",
                "false negative",
                "precision",
                "recall",
                "accuracy",
                "f1 score",
                "n_true",
                "n_pred",
                "mean_true_score",
                "mean_matched_score",
                "panoptic_quality",
            ]
        )

        for raw, gt_image, pred_image in triplets:
            test_prediction = io.imread(pred_image)
            test_ground_truth_image = io.imread(gt_image)
            stats = matching(test_ground_truth_image, test_prediction, thresh=0.5)

            prediction_mask = _binary_mask(test_prediction)
            ground_truth_mask = _binary_mask(test_ground_truth_image)
            intersection = np.logical_and(ground_truth_mask, prediction_mask)
            union = np.logical_or(ground_truth_mask, prediction_mask)
            iou_score = np.sum(intersection) / np.sum(union)

            writer.writerow(
                [
                    model_name,
                    raw.name,
                    str(iou_score),
                    str(stats.fp),
                    str(stats.tp),
                    str(stats.fn),
                    str(stats.precision),
                    str(stats.recall),
                    str(stats.accuracy),
                    str(stats.f1),
                    str(stats.n_true),
                    str(stats.n_pred),
                    str(stats.mean_true_score),
                    str(stats.mean_matched_score),
                    str(stats.panoptic_quality),
                ]
            )


def main():
    args = parse_args()
    data_folder = args.dir[0]
    model_name = args.model[0]
    out_dir = args.out_dir
    print(data_folder)
    print(model_name)
    compare_labels(model_name, data_folder, out_dir)


if __name__ == "__main__":
    main()
